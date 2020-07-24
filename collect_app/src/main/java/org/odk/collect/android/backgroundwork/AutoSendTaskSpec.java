/*
 * Copyright (C) 2018 Nafundi
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.odk.collect.android.backgroundwork;

import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.work.WorkerParameters;

import org.jetbrains.annotations.NotNull;
import org.odk.collect.android.R;
import org.odk.collect.android.analytics.Analytics;
import org.odk.collect.android.application.Collect;
import org.odk.collect.android.dao.FormsDao;
import org.odk.collect.android.dao.InstancesDao;
import org.odk.collect.android.forms.Form;
import org.odk.collect.android.instances.Instance;
import org.odk.collect.android.logic.PropertyManager;
import org.odk.collect.android.network.NetworkStateProvider;
import org.odk.collect.android.notifications.Notifier;
import org.odk.collect.android.openrosa.OpenRosaHttpInterface;
import org.odk.collect.android.preferences.GeneralKeys;
import org.odk.collect.android.preferences.GeneralSharedPreferences;
import org.odk.collect.android.provider.InstanceProviderAPI.InstanceColumns;
import org.odk.collect.android.storage.migration.StorageMigrationRepository;
import org.odk.collect.android.upload.InstanceGoogleSheetsUploader;
import org.odk.collect.android.upload.InstanceServerUploader;
import org.odk.collect.android.upload.InstanceUploader;
import org.odk.collect.android.upload.UploadException;
import org.odk.collect.android.utilities.InstanceUploaderUtils;
import org.odk.collect.android.utilities.PermissionUtils;
import org.odk.collect.android.utilities.WebCredentialsUtils;
import org.odk.collect.android.utilities.gdrive.GoogleAccountsManager;
import org.odk.collect.async.TaskSpec;
import org.odk.collect.async.WorkerAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.inject.Inject;

import timber.log.Timber;

import static org.odk.collect.android.analytics.AnalyticsEvents.SUBMISSION;
import static org.odk.collect.android.provider.FormsProviderAPI.FormsColumns.AUTO_SEND;
import static org.odk.collect.android.utilities.InstanceUploaderUtils.SPREADSHEET_UPLOADED_TO_GOOGLE_DRIVE;

public class AutoSendTaskSpec implements TaskSpec {

    @Inject
    StorageMigrationRepository storageMigrationRepository;

    @Inject
    NetworkStateProvider connectivityProvider;

    @Inject
    Analytics analytics;

    @Inject
    Notifier notifier;

    /**
     * If the app-level auto-send setting is enabled, send all finalized forms that don't specify not
     * to auto-send at the form level. If the app-level auto-send setting is disabled, send all
     * finalized forms that specify to send at the form level.
     *
     * Fails immediately if:
     *   - storage isn't ready
     *   - the network type that toggled on is not the desired type AND no form specifies auto-send
     *
     * If the network type doesn't match the auto-send settings, retry next time a connection is
     * available.
     */
    @NotNull
    @Override
    public Supplier<Boolean> getTask(@NotNull Context context) {
        return () -> {
            Collect.getInstance().getComponent().inject(this);

            if (storageMigrationRepository.isMigrationBeingPerformed()) {
                return true;
            }

            NetworkInfo currentNetworkInfo = connectivityProvider.getNetworkInfo();
            if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)
                    || !(networkTypeMatchesAutoSendSetting(currentNetworkInfo) || atLeastOneFormSpecifiesAutoSend())) {
                if (!networkTypeMatchesAutoSendSetting(currentNetworkInfo)) {
                    return false;
                }

                return true;
            }

            List<Instance> toUpload = getInstancesToAutoSend(GeneralSharedPreferences.isAutoSendEnabled());

            if (toUpload.isEmpty()) {
                return true;
            }

            GeneralSharedPreferences settings = GeneralSharedPreferences.getInstance();
            String protocol = (String) settings.get(GeneralKeys.KEY_PROTOCOL);

            InstanceUploader uploader;
            Map<String, String> resultMessagesByInstanceId = new HashMap<>();
            String deviceId = null;
            boolean anyFailure = false;

            if (protocol.equals(Collect.getInstance().getString(R.string.protocol_google_sheets))) {
                if (PermissionUtils.isGetAccountsPermissionGranted(Collect.getInstance())) {
                    GoogleAccountsManager accountsManager = new GoogleAccountsManager(Collect.getInstance());
                    String googleUsername = accountsManager.getLastSelectedAccountIfValid();
                    if (googleUsername.isEmpty()) {
                        notifier.onSubmission(true, Collect.getInstance().getString(R.string.google_set_account));
                        return true;
                    }
                    accountsManager.selectAccount(googleUsername);
                    uploader = new InstanceGoogleSheetsUploader(accountsManager);
                } else {
                    notifier.onSubmission(true, Collect.getInstance().getString(R.string.odk_permissions_fail));
                    return true;
                }
            } else {
                OpenRosaHttpInterface httpInterface = Collect.getInstance().getComponent().openRosaHttpInterface();
                uploader = new InstanceServerUploader(httpInterface,
                        new WebCredentialsUtils(), new HashMap<>());
                deviceId = new PropertyManager(Collect.getInstance().getApplicationContext())
                        .getSingularProperty(PropertyManager.withUri(PropertyManager.PROPMGR_DEVICE_ID));
            }

            for (Instance instance : toUpload) {
                try {
                    String destinationUrl = uploader.getUrlToSubmitTo(instance, deviceId, null);
                    if (protocol.equals(Collect.getInstance().getString(R.string.protocol_google_sheets))
                            && !InstanceUploaderUtils.doesUrlRefersToGoogleSheetsFile(destinationUrl)) {
                        anyFailure = true;
                        resultMessagesByInstanceId.put(instance.getDatabaseId().toString(), SPREADSHEET_UPLOADED_TO_GOOGLE_DRIVE);
                        continue;
                    }
                    String customMessage = uploader.uploadOneSubmission(instance, destinationUrl);
                    resultMessagesByInstanceId.put(instance.getDatabaseId().toString(), customMessage != null ? customMessage : Collect.getInstance().getString(R.string.success));

                    // If the submission was successful, delete the instance if either the app-level
                    // delete preference is set or the form definition requests auto-deletion.
                    // TODO: this could take some time so might be better to do in a separate process,
                    // perhaps another worker. It also feels like this could fail and if so should be
                    // communicated to the user. Maybe successful delete should also be communicated?
                    if (InstanceUploader.formShouldBeAutoDeleted(instance.getJrFormId(),
                            (boolean) GeneralSharedPreferences.getInstance().get(GeneralKeys.KEY_DELETE_AFTER_SEND))) {
                        Uri deleteForm = Uri.withAppendedPath(InstanceColumns.CONTENT_URI, instance.getDatabaseId().toString());
                        Collect.getInstance().getContentResolver().delete(deleteForm, null, null);
                    }

                    String action = protocol.equals(Collect.getInstance().getString(R.string.protocol_google_sheets)) ?
                            "HTTP-Sheets auto" : "HTTP auto";
                    String label = Collect.getFormIdentifierHash(instance.getJrFormId(), instance.getJrVersion());
                    analytics.logEvent(SUBMISSION, action, label);
                } catch (UploadException e) {
                    Timber.d(e);
                    anyFailure = true;
                    resultMessagesByInstanceId.put(instance.getDatabaseId().toString(),
                            e.getDisplayMessage());
                }
            }

            notifier.onSubmission(anyFailure, InstanceUploaderUtils.getUploadResultMessage(Collect.getInstance(), resultMessagesByInstanceId));
            return true;
        };
    }

    @NotNull
    @Override
    public Class<? extends WorkerAdapter> getWorkManagerAdapter() {
        return Adapter.class;
    }

    /**
     * Returns whether the currently-available connection type is included in the app-level auto-send
     * settings.
     *
     * @return true if a connection is available and settings specify it should trigger auto-send,
     * false otherwise.
     */
    private boolean networkTypeMatchesAutoSendSetting(NetworkInfo currentNetworkInfo) {
        if (currentNetworkInfo == null) {
            return false;
        }

        String autosend = (String) GeneralSharedPreferences.getInstance().get(GeneralKeys.KEY_AUTOSEND);
        boolean sendwifi = autosend.equals("wifi_only");
        boolean sendnetwork = autosend.equals("cellular_only");
        if (autosend.equals("wifi_and_cellular")) {
            sendwifi = true;
            sendnetwork = true;
        }

        return currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI
                && sendwifi || currentNetworkInfo.getType() == ConnectivityManager.TYPE_MOBILE
                && sendnetwork;
    }

    /**
     * Returns instances that need to be auto-sent.
     */
    @NonNull
    private List<Instance> getInstancesToAutoSend(boolean isAutoSendAppSettingEnabled) {
        InstancesDao dao = new InstancesDao();
        Cursor c = dao.getFinalizedInstancesCursor();
        List<Instance> allFinalized = dao.getInstancesFromCursor(c);

        List<Instance> toUpload = new ArrayList<>();
        for (Instance instance : allFinalized) {
            if (formShouldBeAutoSent(instance.getJrFormId(), isAutoSendAppSettingEnabled)) {
                toUpload.add(instance);
            }
        }

        return toUpload;
    }

    /**
     * Returns whether a form with the specified form_id should be auto-sent given the current
     * app-level auto-send settings. Returns false if there is no form with the specified form_id.
     *
     * A form should be auto-sent if auto-send is on at the app level AND this form doesn't override
     * auto-send settings OR if auto-send is on at the form-level.
     *
     * @param isAutoSendAppSettingEnabled whether the auto-send option is enabled at the app level
     */
    public static boolean formShouldBeAutoSent(String jrFormId, boolean isAutoSendAppSettingEnabled) {
        Cursor cursor = new FormsDao().getFormsCursorForFormId(jrFormId);
        String formLevelAutoSend = null;
        if (cursor != null && cursor.moveToFirst()) {
            try {
                int autoSendColumnIndex = cursor.getColumnIndex(AUTO_SEND);
                formLevelAutoSend = cursor.getString(autoSendColumnIndex);
            } finally {
                cursor.close();
            }
        }

        return formLevelAutoSend == null ? isAutoSendAppSettingEnabled
                : Boolean.valueOf(formLevelAutoSend);
    }

    /**
     * Returns true if at least one form currently on the device specifies that all of its filled
     * forms should auto-send no matter the connection type.
     *
     * TODO: figure out where this should live
     */
    private boolean atLeastOneFormSpecifiesAutoSend() {
        FormsDao dao = new FormsDao();

        try (Cursor cursor = dao.getFormsCursor()) {
            List<Form> forms = dao.getFormsFromCursor(cursor);
            for (Form form : forms) {
                if (Boolean.valueOf(form.getAutoSend())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static class Adapter extends WorkerAdapter {

        public Adapter(@NotNull Context context, @NotNull WorkerParameters workerParams) {
            super(new AutoSendTaskSpec(), context, workerParams);
        }
    }
}