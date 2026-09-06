package com.example.ar_glass_plus.settings

import android.content.Context
import com.example.ar_glass_plus.input.CursorController
import com.example.ar_glass_plus.workspace.CalibrationDraft

/**
 * Small, app-private preference store for wearer-specific values.
 *
 * Calibration is only committed when the user explicitly saves the
 * calibration page. Live edits continue to flow through WorkspaceState so
 * the glasses update immediately without turning every slider tick into a
 * persistent write.
 */
class UserPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        FILE_NAME,
        Context.MODE_PRIVATE,
    )

    fun loadPointerSensitivity(): Float = preferences
        .getFloat(KEY_POINTER_SENSITIVITY, CursorController.DEFAULT_SENSITIVITY)
        .coerceIn(MIN_POINTER_SENSITIVITY, MAX_POINTER_SENSITIVITY)

    fun savePointerSensitivity(value: Float) {
        preferences.edit()
            .putFloat(
                KEY_POINTER_SENSITIVITY,
                value.coerceIn(MIN_POINTER_SENSITIVITY, MAX_POINTER_SENSITIVITY),
            )
            .apply()
    }

    fun loadAutoConfirmProjection(): Boolean =
        preferences.getBoolean(KEY_AUTO_CONFIRM_PROJECTION, true)

    fun saveAutoConfirmProjection(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_AUTO_CONFIRM_PROJECTION, enabled)
            .apply()
    }

    fun loadCalibration(): CalibrationDraft {
        if (preferences.getInt(KEY_CALIBRATION_SCHEMA, 0) != CALIBRATION_SCHEMA) {
            return CalibrationDraft.default()
        }
        return CalibrationDraft(
            eyeOrderLeftFirst = preferences.getBoolean(KEY_EYE_ORDER_LEFT_FIRST, true),
            ipdMeters = preferences.getFloat(
                KEY_IPD_METERS,
                CalibrationDraft.default().ipdMeters,
            ),
            leftCenterX = preferences.getFloat(KEY_LEFT_CENTER_X, 960f),
            leftCenterY = preferences.getFloat(KEY_LEFT_CENTER_Y, 540f),
            rightCenterX = preferences.getFloat(KEY_RIGHT_CENTER_X, 960f),
            rightCenterY = preferences.getFloat(KEY_RIGHT_CENTER_Y, 540f),
            fovYDegrees = preferences.getFloat(
                KEY_FOV_Y_DEGREES,
                CalibrationDraft.default().fovYDegrees,
            ),
        ).normalized()
    }

    fun saveCalibration(draft: CalibrationDraft) {
        val value = draft.normalized()
        preferences.edit()
            .putInt(KEY_CALIBRATION_SCHEMA, CALIBRATION_SCHEMA)
            .putBoolean(KEY_EYE_ORDER_LEFT_FIRST, value.eyeOrderLeftFirst)
            .putFloat(KEY_IPD_METERS, value.ipdMeters)
            .putFloat(KEY_LEFT_CENTER_X, value.leftCenterX)
            .putFloat(KEY_LEFT_CENTER_Y, value.leftCenterY)
            .putFloat(KEY_RIGHT_CENTER_X, value.rightCenterX)
            .putFloat(KEY_RIGHT_CENTER_Y, value.rightCenterY)
            .putFloat(KEY_FOV_Y_DEGREES, value.fovYDegrees)
            .apply()
    }

    companion object {
        const val MIN_POINTER_SENSITIVITY = 0.5f
        const val MAX_POINTER_SENSITIVITY = 2f

        private const val FILE_NAME = "ar_glass_plus_user_settings"
        private const val CALIBRATION_SCHEMA = 1
        private const val KEY_CALIBRATION_SCHEMA = "calibration_schema"
        private const val KEY_POINTER_SENSITIVITY = "pointer_sensitivity"
        private const val KEY_AUTO_CONFIRM_PROJECTION = "auto_confirm_projection"
        private const val KEY_EYE_ORDER_LEFT_FIRST = "eye_order_left_first"
        private const val KEY_IPD_METERS = "ipd_meters"
        private const val KEY_LEFT_CENTER_X = "left_center_x"
        private const val KEY_LEFT_CENTER_Y = "left_center_y"
        private const val KEY_RIGHT_CENTER_X = "right_center_x"
        private const val KEY_RIGHT_CENTER_Y = "right_center_y"
        private const val KEY_FOV_Y_DEGREES = "fov_y_degrees"
    }
}
