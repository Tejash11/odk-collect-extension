package io.samagra.odk.collect.extension.handlers

import android.app.Application
import android.content.Context
import android.content.Intent
import io.samagra.odk.collect.extension.components.DaggerFormsDatabaseInteractorComponent
import io.samagra.odk.collect.extension.components.DaggerFormsInteractorComponent
import io.samagra.odk.collect.extension.components.DaggerFormsNetworkInteractorComponent
import io.samagra.odk.collect.extension.interactors.FormsDatabaseInteractor
import io.samagra.odk.collect.extension.interactors.FormsInteractor
import io.samagra.odk.collect.extension.interactors.FormsNetworkInteractor
import io.samagra.odk.collect.extension.interactors.ODKInteractor
import io.samagra.odk.collect.extension.listeners.FileDownloadListener
import io.samagra.odk.collect.extension.listeners.FormsProcessListener
import io.samagra.odk.collect.extension.listeners.ODKProcessListener
import io.samagra.odk.collect.extension.utilities.ConfigHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.odk.collect.android.activities.FormEntryActivity
import org.odk.collect.android.activities.FormHierarchyActivity
import org.odk.collect.android.external.InstancesContract
import org.odk.collect.android.injection.config.DaggerAppDependencyComponent
import org.odk.collect.android.utilities.ApplicationConstants
import org.odk.collect.forms.instances.Instance
import org.odk.collect.forms.instances.InstancesRepository
import java.io.File
import javax.inject.Inject

class ODKHandler @Inject constructor(
    private val application: Application
): ODKInteractor {

    private lateinit var formsNetworkInteractor: FormsNetworkInteractor
    private lateinit var formsDatabaseInteractor: FormsDatabaseInteractor
    private lateinit var formsInteractor: FormsInteractor
    private lateinit var instancesRepository: InstancesRepository

    override fun setupODK(settingsJson: String, lazyDownload: Boolean, listener: ODKProcessListener) {
        try {
            ConfigHandler(application).configure(settingsJson)
            formsNetworkInteractor = DaggerFormsNetworkInteractorComponent.factory().create(application).getFormsNetworkInteractor()
            formsDatabaseInteractor = DaggerFormsDatabaseInteractorComponent.factory().create(application).getFormsDatabaseInteractor()
            formsInteractor = DaggerFormsInteractorComponent.factory().create(application).getFormsInteractor()
            instancesRepository = DaggerAppDependencyComponent.builder().application(application).build().instancesRepositoryProvider().get()

            if (!lazyDownload) {
                formsNetworkInteractor.downloadRequiredForms(object: FileDownloadListener {
                    override fun onProgress(progress: Int) { listener.onProgress(progress) }
                    override fun onComplete(downloadedFile: File) { listener.onProcessComplete() }
                    override fun onCancelled(exception: Exception) { listener.onProcessingError(exception) }
                })
            }
            else {
                listener.onProcessComplete()
            }
        } catch (e: IllegalStateException) {
            listener.onProcessingError(e)
        }
    }

    override fun resetODK(listener: ODKProcessListener) {
        CoroutineScope(Job()).launch{ ConfigHandler(application).reset(listener) }
    }

    override fun openForm(formId: String, context: Context, listener: FormsProcessListener) {
        CoroutineScope(Job()).launch {
            // Delete any saved instances of this form
            val savedInstances = instancesRepository.getAllByFormId(formId)
            for (instance in savedInstances) {
                if (instance.status == Instance.STATUS_INCOMPLETE) {
                    instancesRepository.delete(instance.dbId)
                }
            }
            val requiredForm = formsDatabaseInteractor.getLatestFormById(formId)
            if (requiredForm == null) {
                downloadAndOpenForm(formId, context, listener)
            }
            else {
                val xmlFile = File(requiredForm.formFilePath)
                // Media Files check removed since the database always returns a media path
                // even if there may be no media related to the form itself.
                // TODO: ODK UPGRADE: Find a workaround, maybe try modifying the database
                // during download by reading the xml media attachment content.
//                val mediaPath = requiredForm.formMediaPath ?: ""
//                val mediaFolder = File(mediaPath)
                if (xmlFile.exists()) {
                    listener.onProcessed()
                    formsInteractor.openFormWithFormId(formId, context)
                }
                else {
                    xmlFile.delete()
//                    mediaFolder.deleteRecursively()
                    formsDatabaseInteractor.deleteByFormId(formId)
                    downloadAndOpenForm(formId, context, listener)
                }
            }
        }
    }

    override fun openSavedForm(formId: String, context: Context, listener: FormsProcessListener) {
        CoroutineScope(Job()).launch {
            val formInstances = instancesRepository.getAllByFormId(formId)
            var savedInstance: Instance? = null
            for (instance in formInstances) {
                if (instance.status == Instance.STATUS_INCOMPLETE) {
                    savedInstance = instance
                }
            }
            if (savedInstance == null) {
                openForm(formId, context, listener)
            }
            else {
                val currentProjectProvider = DaggerAppDependencyComponent.builder().application(application).build().currentProjectProvider()
                val instanceUri = InstancesContract.getUri(currentProjectProvider.getCurrentProject().uuid, savedInstance.dbId)
                val intent = Intent(context, FormEntryActivity::class.java)
                intent.action = Intent.ACTION_EDIT
                intent.data = instanceUri
                intent.putExtra(ApplicationConstants.BundleKeys.FORM_MODE, ApplicationConstants.FormModes.EDIT_SAVED)
                intent.putExtra(FormHierarchyActivity.EXTRA_JUMP_TO_BEGINNING, true)
                context.startActivity(intent)
                listener.onProcessed()
            }
        }
    }

    private fun downloadAndOpenForm(formId: String, context: Context, listener: FormsProcessListener) {
        formsNetworkInteractor.downloadFormById(formId, object : FileDownloadListener {
            override fun onProgress(progress: Int) {}
            override fun onComplete(downloadedFile: File) {
                listener.onProcessed()
                formsInteractor.openFormWithFormId(formId, context)
            }
            override fun onCancelled(exception: Exception) {
                listener.onProcessingError(exception)
            }
        })
    }
}