package com.example.ar_glass_plus.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectionConsentAutomatorTest {

    @Test
    fun exactCapturedDialogReturnsLivePositiveButtonCenter() {
        val xml = hierarchy(
            title = "是否开始投屏？",
            message = "将本设备屏幕内容投射到外接显示屏",
            positiveText = "开始",
            positiveId = "android:id/button1",
            positiveBounds = "[1401,1048][1850,1193]",
        )

        val target = ProjectionConsentDialogMatcher.findPositiveButton(xml)!!

        assertEquals(1625, target.centerX)
        assertEquals(1120, target.centerY)
    }

    @Test
    fun refusesAnotherSystemUiDialogEvenWithPositiveButton() {
        val xml = hierarchy(
            title = "是否关机？",
            message = "设备将关闭",
            positiveText = "确定",
            positiveId = "android:id/button1",
            positiveBounds = "[1401,1048][1850,1193]",
        )

        assertNull(ProjectionConsentDialogMatcher.findPositiveButton(xml))
    }

    @Test
    fun refusesRightTextWhenPositiveResourceIdDoesNotMatch() {
        val xml = hierarchy(
            title = "是否开始投屏？",
            message = "将本设备屏幕内容投射到外接显示屏",
            positiveText = "开始",
            positiveId = "android:id/button2",
            positiveBounds = "[950,1048][1398,1193]",
        )

        assertNull(ProjectionConsentDialogMatcher.findPositiveButton(xml))
    }

    private fun hierarchy(
        title: String,
        message: String,
        positiveText: String,
        positiveId: String,
        positiveBounds: String,
    ): String = """
        <hierarchy>
          <node package="com.android.systemui">
            <node text="$title" resource-id="com.android.systemui:id/alertTitle" package="com.android.systemui" clickable="false" enabled="true" bounds="[1010,831][1790,896]">
            <node text="$message" resource-id="android:id/message" package="com.android.systemui" clickable="false" enabled="true" bounds="[950,956][1850,1003]">
            <node text="$positiveText" resource-id="$positiveId" package="com.android.systemui" clickable="true" enabled="true" bounds="$positiveBounds">
          </node>
        </hierarchy>
    """.trimIndent()
}
