package org.odk.collect.android.instancemanagement

import org.odk.collect.forms.FormsRepository
import org.odk.collect.forms.instances.Instance
import org.odk.collect.forms.instances.InstancesRepository

class InstanceAutoSendFetcher(private val autoSendSettingChecker: AutoSendSettingChecker) {

    fun getInstancesToAutoSend(projectId: String, instancesRepository: InstancesRepository, formsRepository: FormsRepository): List<Instance> {
        val allFinalizedForms = instancesRepository.getAllByStatus(Instance.STATUS_COMPLETE, Instance.STATUS_SUBMISSION_FAILED)

        return if (autoSendSettingChecker.isAutoSendEnabledInSettings(projectId)) {
            allFinalizedForms
        } else {
            allFinalizedForms.filter {
                formsRepository.getLatestByFormIdAndVersion(it.formId, it.formVersion)?.let { form ->
                    form.autoSend.toBoolean()
                } ?: false
            }
        }
    }
}
