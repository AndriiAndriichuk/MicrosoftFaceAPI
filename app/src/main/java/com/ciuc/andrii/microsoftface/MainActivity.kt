package com.ciuc.andrii.microsoftface

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ciuc.andrii.microsoftface.utils.Constants.Companion.STORAGE_PERMISSION_CODE
import com.ciuc.andrii.microsoftface.utils.Constants.Companion.apiEndpoint
import com.ciuc.andrii.microsoftface.utils.Constants.Companion.logTag
import com.ciuc.andrii.microsoftface.utils.drawFaceRectangleOnBitmap
import com.ciuc.andrii.microsoftface.utils.toast
import com.microsoft.projectoxford.face.FaceServiceRestClient
import com.microsoft.projectoxford.face.contract.*
import com.tbruyelle.rxpermissions2.RxPermissions
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.*

class MainActivity : AppCompatActivity() {

    lateinit var cameraHidden: CameraHidden

    val personGroupId = "persogroup"

    lateinit var faceServiceClient: FaceServiceRestClient
    var faceDetected: Array<Face>? = null
    var currentPhotoPath: String = ""
    var currentBitmap: Bitmap? = null
    var havePermissions = false

    lateinit var rxPermission: RxPermissions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rxPermission = RxPermissions(this)

        faceServiceClient = FaceServiceRestClient(apiEndpoint, resources.getString(R.string.face))

        cameraHidden = CameraHidden(sView = surface)

        btnTakePhoto.performClick()

        cameraHidden.liveData.observe(this, androidx.lifecycle.Observer {
            if (it != null) {

                imageView.setImageBitmap(it)
                currentBitmap = it

                val outputStream = ByteArrayOutputStream()
                it.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                val targetStream = ByteArrayInputStream(outputStream.toByteArray())
                val path = MediaStore.Images.Media.insertImage(this.contentResolver, it, "Title", null)
                currentPhotoPath = path
                Timber.d("%s%s", logTag + "Photo path", currentPhotoPath)

                DetectTask().execute(targetStream)

            } else {
                toast("Error")
            }

        })

        btnTakePhoto.setOnClickListener {
            getPhotoAndIdentify()
        }

    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    private fun addPersonToGroup(groupId: String, personName: String) {
        try {
            val group = faceServiceClient.getPersonGroup(groupId)
            if (group != null) {
                val personResult = faceServiceClient.createPerson(groupId, personName, null)
                detectFaceAndRegister(groupId, personResult)
                Timber.d(getString(R.string.add_person_to_group), logTag)
            } else {
                Timber.d("%s %s", logTag, "Group is NULL")
            }

        } catch (error: Exception) {
            Timber.d("%s %s", logTag, error.toString())
        }
    }

    private fun detectFaceAndRegister(groupId: String, personResult: CreatePersonResult?) {

        try {
            val outputStream = ByteArrayOutputStream()
            currentBitmap?.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            val targetStream = ByteArrayInputStream(outputStream.toByteArray())

            faceServiceClient.addPersonFace(
                personGroupId,
                personResult?.personId,
                targetStream,
                null,
                null
            )
        } catch (e: Exception) {
            Timber.d("%s %s", logTag, e.toString())
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(
                    this
                )
            ) {
                checkPermissions()
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toast(getString(R.string.permission_granted))
                //dispatchTakePictureIntent()

            } else {
                toast(getString(R.string.permission_deined))
                val intent = Intent()
                intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivityForResult(intent, requestCode)
            }
        }
    }

    inner class DetectTask : AsyncTask<InputStream, String, Array<Face>>() {
        private val mDialog = ProgressDialog(this@MainActivity)

        override fun doInBackground(vararg params: InputStream): Array<Face>? {
            return try {
                publishProgress(getString(R.string.detecting))
                val results = faceServiceClient.detect(params[0], true, false, null)
                if (results == null) {
                    publishProgress(getString(R.string.detecting_finished))
                    null
                } else {
                    publishProgress(String.format(getString(R.string.detecting_finished_good), results.size))
                    results
                }
            } catch (ex: Exception) {
                Timber.d("%s %s", logTag, ex.toString())
                null
            }

        }

        override fun onPreExecute() {
            mDialog.show()
        }

        override fun onPostExecute(faces: Array<Face>) {
            mDialog.dismiss()
            faceDetected = faces
            var options: BitmapFactory.Options = BitmapFactory.Options()
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            imageView.setImageBitmap(
                currentBitmap?.let {
                    drawFaceRectangleOnBitmap(
                        it,
                        faceDetected,
                        ""
                    )
                }
            )

            if (faceDetected != null && faceDetected!!.isNotEmpty()) {
                val faceIds = arrayOfNulls<UUID>(faceDetected!!.size)

                for (i: Int in faceDetected!!.indices) {
                    faceIds[i] = faceDetected!![i].faceId
                }
                IdentifyTask().execute(*faceIds)
            } else {
                getPhotoAndIdentify()
            }

        }

        override fun onProgressUpdate(vararg values: String) {
            mDialog.setMessage(values[0])
            Toast.makeText(this@MainActivity, values[0], Toast.LENGTH_SHORT).show()
        }
    }

    private inner class IdentifyTask :
        AsyncTask<UUID, String, Array<IdentifyResult>>() {

        private val mDialog = ProgressDialog(this@MainActivity)

        @SuppressLint("BinaryOperationInTimber")
        override fun doInBackground(params: Array<UUID>): Array<IdentifyResult>? {

            try {
                publishProgress(getString(R.string.getting_group_status))
                val trainingStatus = faceServiceClient.getPersonGroupTrainingStatus(personGroupId)
                if (trainingStatus.status != TrainingStatus.Status.Succeeded) {
                    publishProgress(getString(R.string.person_group_status) + trainingStatus.status)
                    return null
                }
                Timber.d(getString(R.string.identifying), logTag)

                Timber.d(getString(R.string.params), logTag)
                params.forEach {
                    Timber.d(logTag + '\t' + it.toString())
                }

                return faceServiceClient.identityInPersonGroup(
                    personGroupId, // person group id
                    params // face ids
                    , 1
                )

            } catch (e: Exception) {
                Timber.d("%s%s", logTag, e.toString())
                return null
            }

        }

        override fun onPreExecute() {
            mDialog.show()
        }

        override fun onPostExecute(identifyResults: Array<IdentifyResult>?) {
            mDialog.dismiss()

            if (identifyResults != null && identifyResults.isNotEmpty()) {


                val filteredArray = identifyResults.filter { it.candidates.isNotEmpty() }
                if (filteredArray.isNotEmpty()) {

                    filteredArray.forEach {
                        it.candidates.sortBy { it.confidence }
                    }

                    val el = filteredArray.maxBy { it.candidates[it.candidates.size - 1].confidence }
                    if (el != null) {
                        PersonDetectionTask(personGroupId).execute(el.candidates[0].personId)
                    }
                } else {
                    onIdentifyNull()
                }

                Timber.d(getString(R.string.identify_result_not_null), logTag)
            } else {
                onIdentifyNull()
            }

        }

        override fun onProgressUpdate(vararg values: String) {
            mDialog.setMessage(values[0])
        }
    }

    private fun onIdentifyNull() {
        toast(getString(R.string.cannot_identify_face))
        Timber.d(getString(R.string.identify_result_null), logTag)
        showAddPeopleDialog()
    }

    private inner class PersonDetectionTask(private val personGroupId: String) : AsyncTask<UUID, String, Person>() {
        private val mDialog = ProgressDialog(this@MainActivity)

        override fun doInBackground(vararg params: UUID): Person? {
            return try {
                publishProgress(getString(R.string.getting_group_status))
                faceServiceClient.getPerson(personGroupId, params[0])
            } catch (e: Exception) {
                Timber.d("%s%s", logTag, e.toString())
                null
            }

        }

        override fun onPreExecute() {
            mDialog.show()
        }

        override fun onPostExecute(person: Person) {
            mDialog.dismiss()
            val options: BitmapFactory.Options = BitmapFactory.Options()
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            imageView.setImageBitmap(
                currentBitmap?.let {
                    drawFaceRectangleOnBitmap(
                        it,
                        faceDetected,
                        person.name
                    )
                }
            )
        }

        override fun onProgressUpdate(vararg values: String) {
            mDialog.setMessage(values[0])
        }
    }


    private fun showAddPeopleDialog() {
        val thisDialog = Dialog(this)
        thisDialog.setTitle(getString(R.string.input_person_name))
        thisDialog.setCancelable(false)
        thisDialog.setContentView(R.layout.dialog_add_person)
        val editPersonName = thisDialog.findViewById<EditText>(R.id.editPersonName)
        val btnAddPerson = thisDialog.findViewById<Button>(R.id.btnAddPerson)

        btnAddPerson.setOnClickListener {
            if (editPersonName.text.toString().isNotEmpty()) {
                GlobalScope.launch {
                    addPersonToGroup(personGroupId, editPersonName.text.toString())
                    faceServiceClient.trainPersonGroup(personGroupId)

                    Timber.d(getString(R.string.waiting_for_training), logTag)
                    delay(100)
                    var training = faceServiceClient.getPersonGroupTrainingStatus(personGroupId)
                    if (training.status != TrainingStatus.Status.Running) {
                        Timber.d(logTag + "Status: ${training.status}")

                    }
                }

                thisDialog.cancel()
            } else {
                toast(getString(R.string.please_input_person_name))
            }
        }
        thisDialog.show()
    }


    private fun getPhotoAndIdentify() {
        if (havePermissions) {
            cameraHidden.init()
        } else {
            checkPermissions()
        }
    }

    private fun checkPermissions() {

        val permissionResult = rxPermission
            .requestEach(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
            .subscribe {
                when {
                    it.granted -> {
                        havePermissions = true
                    }
                    it.shouldShowRequestPermissionRationale -> {
                        havePermissions = false
                        // Denied permission without ask never again
                        val intent = Intent()
                        intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        val uri = Uri.fromParts("package", packageName, null)
                        intent.data = uri
                        startActivityForResult(intent, STORAGE_PERMISSION_CODE)

                    }
                    else -> {
                        havePermissions = false
                        val intent = Intent()
                        intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        val uri = Uri.fromParts("package", packageName, null)
                        intent.data = uri
                        startActivityForResult(intent, STORAGE_PERMISSION_CODE)

                    }
                }
            }

    }

}



