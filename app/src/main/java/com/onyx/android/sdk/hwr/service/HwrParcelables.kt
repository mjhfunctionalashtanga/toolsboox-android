package com.onyx.android.sdk.hwr.service

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable

/*
 * The three parcelables the Boox HWR service speaks. The PARCEL LAYOUT here is the wire —
 * field order, the leading bean-class-name string, the hand-written PFD framing — and is
 * dictated by the firmware's own com.onyx.android.sdk.hwr.bean classes; nothing about it is
 * ours to improve. Layout facts learned from the Aragonite project's published interface
 * research (github.com/jdkruzr/aragonite, GPL-3.0 — used as documentation; this file is an
 * independent implementation).
 */

/** What the recognizer is asked to be: language, content kind, engine, and the canvas frame. */
class HWRInputArgs() : Parcelable {
    var lang: String = "en_US"
    var contentType: String = "Text"
    var recognizerType: String = "Text"
    var viewWidth: Float = 0f
    var viewHeight: Float = 0f
    var offsetX: Float = 0f
    var offsetY: Float = 0f
    var isGestureEnable: Boolean = false
    var isTextEnable: Boolean = true
    var isShapeEnable: Boolean = false
    var isIncremental: Boolean = false
    var pfd: ParcelFileDescriptor? = null
    var content: String? = null

    private constructor(p: Parcel) : this() {
        // The service writes its bean's class name first; a null there means "no body".
        if (p.readString() != null) {
            lang = p.readString() ?: "en_US"
            contentType = p.readString() ?: "Text"
            recognizerType = p.readString() ?: "Text"
            viewWidth = p.readFloat(); viewHeight = p.readFloat()
            offsetX = p.readFloat(); offsetY = p.readFloat()
            isGestureEnable = p.readByte() != 0.toByte()
            isTextEnable = p.readByte() != 0.toByte()
            isShapeEnable = p.readByte() != 0.toByte()
            isIncremental = p.readByte() != 0.toByte()
        }
        pfd = p.readParcelable(ParcelFileDescriptor::class.java.classLoader)
        content = p.readString()
    }

    override fun writeToParcel(p: Parcel, flags: Int) {
        p.writeString("com.onyx.android.sdk.hwr.bean.HWRInputData")
        p.writeString(lang); p.writeString(contentType); p.writeString(recognizerType)
        p.writeFloat(viewWidth); p.writeFloat(viewHeight)
        p.writeFloat(offsetX); p.writeFloat(offsetY)
        p.writeByte(if (isGestureEnable) 1 else 0)
        p.writeByte(if (isTextEnable) 1 else 0)
        p.writeByte(if (isShapeEnable) 1 else 0)
        p.writeByte(if (isIncremental) 1 else 0)
        // The PFD rides with a hand-written class-name frame, not writeParcelable — the
        // service's reader expects exactly this shape.
        val fd = pfd
        if (fd != null) {
            p.writeString("android.os.ParcelFileDescriptor")
            fd.writeToParcel(p, flags)
        } else p.writeString(null)
        p.writeString(content)
    }

    override fun describeContents() = 0

    companion object CREATOR : Parcelable.Creator<HWRInputArgs> {
        override fun createFromParcel(p: Parcel) = HWRInputArgs(p)
        override fun newArray(size: Int) = arrayOfNulls<HWRInputArgs>(size)
    }
}

/** What comes back: activation/compile flags on init, and on a batch run either an error in
 *  [hwrResult] or the recognition JSON behind [pfd]. */
class HWROutputArgs() : Parcelable {
    var pfd: ParcelFileDescriptor? = null
    var recognizerActivated: Boolean = false
    var compileSuccess: Boolean = false
    var hwrResult: String? = null
    var gesture: String? = null
    var outputType: Int = 0
    var itemIdMap: String? = null

    private constructor(p: Parcel) : this() {
        pfd = p.readParcelable(ParcelFileDescriptor::class.java.classLoader)
        recognizerActivated = p.readByte() != 0.toByte()
        compileSuccess = p.readByte() != 0.toByte()
        hwrResult = p.readString()
        gesture = p.readString()
        outputType = p.readInt()
        itemIdMap = p.readString()
    }

    override fun writeToParcel(p: Parcel, flags: Int) {
        p.writeParcelable(pfd, flags)
        p.writeByte(if (recognizerActivated) 1 else 0)
        p.writeByte(if (compileSuccess) 1 else 0)
        p.writeString(hwrResult); p.writeString(gesture)
        p.writeInt(outputType); p.writeString(itemIdMap)
    }

    override fun describeContents() = 0

    companion object CREATOR : Parcelable.Creator<HWROutputArgs> {
        override fun createFromParcel(p: Parcel) = HWROutputArgs(p)
        override fun newArray(size: Int) = arrayOfNulls<HWROutputArgs>(size)
    }
}

/** Present in the interface, empty on the wire — declared because transaction 5 names it. */
class HWRCommandArgs() : Parcelable {
    private constructor(p: Parcel) : this()
    override fun writeToParcel(p: Parcel, flags: Int) {}
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<HWRCommandArgs> {
        override fun createFromParcel(p: Parcel) = HWRCommandArgs(p)
        override fun newArray(size: Int) = arrayOfNulls<HWRCommandArgs>(size)
    }
}
