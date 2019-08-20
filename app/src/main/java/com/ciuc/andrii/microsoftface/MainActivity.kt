package com.ciuc.andrii.microsoftface

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import com.ciuc.andrii.microsoftface.utils.Constants.Companion.REQUEST_CODE_CAMERA
import com.ciuc.andrii.microsoftface.utils.Constants.Companion.STORAGE_PERMISSION_CODE
import com.ciuc.andrii.microsoftface.utils.Constants.Companion.apiEndpoint
import com.ciuc.andrii.microsoftface.utils.Constants.Companion.providerAuthorities
import com.ciuc.andrii.microsoftface.utils.drawFaceRectangleOnBitmap
import com.ciuc.andrii.microsoftface.utils.toast
import com.microsoft.projectoxford.face.FaceServiceRestClient
import com.microsoft.projectoxford.face.contract.*
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    val personGroupId = "persogroup"
    val logTag = "test_detecting"

    lateinit var faceServiceClient: FaceServiceRestClient
    var faceDetected: Array<Face>? = null
    var currentPhotoPath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        faceServiceClient = FaceServiceRestClient(apiEndpoint, resources.getString(R.string.face))

        btnAddPerson.isEnabled = false
        btnIdentify.isEnabled = false

        btnAddPerson.setOnClickListener {
            showAddPeopleDialog()
        }

        btnIdentify.setOnClickListener {
            if (faceDetected != null && faceDetected!!.isNotEmpty()) {
                val faceIds = arrayOfNulls<UUID>(faceDetected!!.size)

                for (i: Int in faceDetected!!.indices) {
                    faceIds[i] = faceDetected!![i].faceId
                }
                IdentifyTask().execute(*faceIds)
            }
        }

        btnTakePhoto.setOnClickListener {
            requestPermissionsCamera()
        }

    }


    private fun addPersonToGroup(groupId: String, personName: String, imagePath: String) {
        try {
            val group = faceServiceClient.getPersonGroup(groupId)
            if (group != null) {
                val personResult = faceServiceClient.createPerson(groupId, personName, null)
                detectFaceAndRegister(groupId, personResult)
                Timber.d(getString(R.string.add_person_to_group), logTag)
            }

        } catch (error: Exception) {
            Timber.d("%s %s", logTag, error.toString())
        }
    }

    private fun detectFaceAndRegister(groupId: String, personResult: CreatePersonResult?) {
        val myFile = File(currentPhotoPath)
        val myUrl = myFile.toURI().toURL()
        val stream = myUrl.openStream()
        faceServiceClient.addPersonFace(
            personGroupId,
            personResult?.personId,
            stream,
            null,
            null
        )

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_CAMERA && resultCode == Activity.RESULT_OK) {
            imageView.setImageURI(Uri.fromFile(File(currentPhotoPath)))
            btnAddPerson.isEnabled = true
            btnIdentify.isEnabled = true
            val myFile = File(currentPhotoPath)
            val targetStream = FileInputStream(myFile)
            DetectTask().execute(targetStream)
        }
        super.onActivityResult(requestCode, resultCode, data)
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toast(getString(R.string.permission_granted))
                dispatchTakePictureIntent()
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

    private fun dispatchTakePictureIntent() {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
            // Ensure that there's a camera activity to handle the intent
            takePictureIntent.resolveActivity(packageManager)?.also {
                // Create the File where the photo should go
                val photoFile: File? = try {
                    createImageFile()
                } catch (ex: IOException) {

                    null
                }
                photoFile?.also {
                    val photoURI: Uri = FileProvider.getUriForFile(
                        this,
                        providerAuthorities,
                        it
                    )
                    takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                    startActivityForResult(takePictureIntent, REQUEST_CODE_CAMERA)
                }
            }
        }
    }


    @Throws(IOException::class)
    private fun createImageFile(): File {
        // Create an image file name
        val timeStamp: String = SimpleDateFormat(getString(R.string.data_format_pattern)).format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "JPEG_${timeStamp}_", /* prefix */
            ".jpg", /* suffix */
            storageDir /* directory */
        ).apply {
            // Save a file: path for use with ACTION_VIEW intents
            currentPhotoPath = absolutePath
        }
    }


    private fun requestPermissionsCamera() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        ) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.permission_required))
                .setMessage(getString(R.string.access_camera))
                .setPositiveButton(getString(R.string.OK)) { _, _ ->
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.CAMERA), STORAGE_PERMISSION_CODE
                    )
                }
                .setNegativeButton(
                    getString(R.string.Cancel)
                ) { dialog, _ -> dialog.dismiss() }
                .create().show()

        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA), STORAGE_PERMISSION_CODE
            )
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
            val bmp: Bitmap? = BitmapFactory.decodeFile(currentPhotoPath, options)
            imageView.setImageBitmap(
                drawFaceRectangleOnBitmap(
                    bmp!!,
                    faceDetected,
                    ""
                )
            )

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
                for (identifyResult in identifyResults) {
                    if (identifyResult.candidates.isNotEmpty()){
                        PersonDetectionTask(personGroupId).execute(identifyResult.candidates[0].personId)
                    }
                }
                Timber.d(getString(R.string.identify_result_not_null), logTag)
            } else {
                toast(getString(R.string.cannot_identify_face))
                Timber.d(getString(R.string.identify_result_null), logTag)
            }

        }

        override fun onProgressUpdate(vararg values: String) {
            mDialog.setMessage(values[0])
        }
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
            val bmp: Bitmap? = BitmapFactory.decodeFile(currentPhotoPath, options)
            imageView.setImageBitmap(
                drawFaceRectangleOnBitmap(
                    bmp!!,
                    faceDetected,
                    person.name
                )
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
                    addPersonToGroup(personGroupId, editPersonName.text.toString(), currentPhotoPath)
                    faceServiceClient.trainPersonGroup(personGroupId)
                }

                GlobalScope.launch {
                    Timber.d(getString(R.string.waiting_for_training), logTag)
                    delay(500)
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
}



