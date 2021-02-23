package org.oppia.android.app.administratorcontrols.administratorcontrolsitemviewmodel

import androidx.databinding.Observable
import androidx.databinding.ObservableField
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import org.oppia.android.app.model.DeviceSettings
import org.oppia.android.app.model.ProfileId
import org.oppia.android.domain.profile.ProfileManagementController
import org.oppia.android.util.data.DataProviders.Companion.toLiveData
import org.oppia.android.util.logging.ConsoleLogger

/** [ViewModel] for the recycler view in [AdministratorControlsFragment]. */
class AdministratorControlsDownloadPermissionsViewModel(
  private val fragment: Fragment,
  private val logger: ConsoleLogger,
  private val profileManagementController: ProfileManagementController,
  private val userProfileId: ProfileId,
  deviceSettings: DeviceSettings
) : AdministratorControlsItemViewModel() {

  val isTopicWifiUpdatePermission =
    ObservableField<Boolean>(deviceSettings.allowDownloadAndUpdateOnlyOnWifi)
  val isTopicAutoUpdatePermission =
    ObservableField<Boolean>(deviceSettings.automaticallyUpdateTopics)

  init {
    val topicWifiUpdatePermissionCallback: Observable.OnPropertyChangedCallback =
      object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
          onTopicWifiUpdatePermissionChanged()
        }
      }
    val topicAutoUpdatePermissionCallback: Observable.OnPropertyChangedCallback =
      object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
          onTopicAutoUpdatePermissionChanged()
        }
      }
    isTopicWifiUpdatePermission.addOnPropertyChangedCallback(topicWifiUpdatePermissionCallback)
    isTopicAutoUpdatePermission.addOnPropertyChangedCallback(topicAutoUpdatePermissionCallback)
  }

  private fun onTopicWifiUpdatePermissionChanged() {
    val checked = isTopicWifiUpdatePermission.get()!!
    profileManagementController.updateWifiPermissionDeviceSettings(userProfileId, checked)
      .toLiveData()
      .observe(
        fragment,
        Observer {
          if (it.isFailure()) {
            logger.e(
              "AdministratorControlsFragment",
              "Failed to update topic update on wifi permission",
              it.getErrorOrNull()!!
            )
          }
        }
      )
  }

  private fun onTopicAutoUpdatePermissionChanged() {
    val checked = isTopicAutoUpdatePermission.get()!!
    profileManagementController.updateTopicAutomaticallyPermissionDeviceSettings(
      userProfileId,
      checked
    ).toLiveData().observe(
      fragment,
      Observer {
        if (it.isFailure()) {
          logger.e(
            "AdministratorControlsFragment",
            "Failed to update topic auto update permission",
            it.getErrorOrNull()!!
          )
        }
      }
    )
  }
}
