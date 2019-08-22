package com.ciuc.andrii.microsoftface

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.Camera
import android.os.Environment
import android.os.StrictMode
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.lifecycle.MutableLiveData
import com.ciuc.andrii.microsoftface.utils.Constants.Companion.logTag
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*


class CameraHidden(private var sView: SurfaceView) {

    private var mCamera: Camera? = null
    private var parameters: Camera.Parameters? = null
    var liveData: MutableLiveData<Bitmap?> = MutableLiveData()
    private var photoPath = ""

    private var mCall: Camera.PictureCallback = Camera.PictureCallback { data, _ ->
        var outStream: FileOutputStream? = null
        try {

            val photoFile = File(Environment.getExternalStorageDirectory(), "A")
            if (!photoFile.exists()) {
                photoFile.mkdirs()
                Timber.d("%s%s", logTag + "folder", Environment.getExternalStorageDirectory())
            }

            val cal = Calendar.getInstance()
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss")
            val tar = sdf.format(cal.time)

            outStream = FileOutputStream(photoPath)
            outStream.write(data)
            outStream.close()


            Timber.d("%s%s", logTag + data.size.toString() + " byte written to:", photoPath)

            photoPath = photoFile.absolutePath + tar + ".jpg"

            val options = BitmapFactory.Options()
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            var bitmap = BitmapFactory.decodeFile(photoFile.absolutePath + tar + ".jpg", options)

            bitmap = rotateImage(bitmap, 270f)

            liveData.value = bitmap

            stopCamera()

        } catch (e: FileNotFoundException) {
            Timber.d("%s%s", logTag, e.message)
            photoPath = ""
        } catch (e: IOException) {
            Timber.d("%s%s", logTag, e.message)
            photoPath = ""
        }
    }

    private fun rotateImage(source: Bitmap?, angle: Float): Bitmap? {
        val matrix = Matrix()
        matrix.postRotate(angle)
        return source?.width?.let {
            Bitmap.createBitmap(
                source, 0, 0, it, source.height, matrix,
                true
            )
        }
    }


    fun init() {
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)


        when {
            Camera.getNumberOfCameras() >= 2 -> mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT)
            Camera.getNumberOfCameras() < 2 -> mCamera = Camera.open()
        }

        try {
            mCamera.let {
                it?.setPreviewDisplay(sView.holder)
                parameters = it?.parameters
                it?.parameters = parameters
                it?.startPreview()
                it?.takePicture(null, null, mCall)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }

        sView.holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)
    }


    private fun stopCamera() {

        if (null == mCamera)
            return
        mCamera?.stopPreview()
        mCamera?.release()
        mCamera = null
        Timber.d("%s closed", logTag)
    }

}