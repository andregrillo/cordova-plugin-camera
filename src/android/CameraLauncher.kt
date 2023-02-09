/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/
package org.apache.cordova.camera

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.outsystems.plugins.camera.controller.*
import com.outsystems.plugins.camera.model.OSCAMRError
import com.outsystems.plugins.camera.model.OSCAMRParameters
import org.apache.cordova.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * This class launches the camera view, allows the user to take a picture, closes the camera view,
 * and returns the captured image.  When the camera view is closed, the screen displayed before
 * the camera view was shown is redisplayed.
 */
class CameraLauncher : CordovaPlugin() {
    private var mQuality // Compression quality hint (0-100: 0=low quality & high compression, 100=compress of max quality)
            = 0
    private var targetWidth // desired width of the image
            = 0
    private var targetHeight // desired height of the image
            = 0
    private var imageUri // Uri of captured image
            : Uri? = null
    private var imageFilePath // File where the image is stored
            : String? = null
    private var encodingType // Type of encoding to use
            = 0
    private var mediaType // What type of media to retrieve
            = 0
    private var destType // Source type (needs to be saved for the permission handling)
            = 0
    private var srcType // Destination type (needs to be saved for permission handling)
            = 0
    private var saveToPhotoAlbum // Should the picture be saved to the device's photo album
            = false
    private var correctOrientation // Should the pictures orientation be corrected
            = false
    private var orientationCorrected // Has the picture's orientation been corrected
            = false
    private var allowEdit // Should we allow the user to crop the image.
            = false
    var callbackContext: CallbackContext? = null
    private var numPics = 0
    private var conn // Used to update gallery app with newly-written files
            : MediaScannerConnection? = null
    private var scanMe // Uri of image to be added to content store
            : Uri? = null
    private var croppedUri: Uri? = null
    private var croppedFilePath: String? = null

    private lateinit var applicationId: String
    private var pendingDeleteMediaUri: Uri? = null
    private var camController: OSCAMRController? = null
    private var camParameters: OSCAMRParameters? = null

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action            The action to execute.
     * @param args              JSONArray of arguments for the plugin.
     * @param callbackContext   The callback id used when calling back into JavaScript.
     * @return                  A PluginResult object with a status and message.
     */
    @Throws(JSONException::class)
    override fun execute(
        action: String,
        args: JSONArray,
        callbackContext: CallbackContext
    ): Boolean {
        this.callbackContext = callbackContext
        //Adding an API to CoreAndroid to get the BuildConfigValue
        //This allows us to not make this a breaking change to embedding
        applicationId =
            BuildHelper.getBuildConfigValue(cordova.activity, "APPLICATION_ID") as String
        applicationId = preferences.getString("applicationId", applicationId)
        camController = OSCAMRController(applicationId, OSCAMRExifHelper(), OSCAMRFileHelper(), OSCAMRMediaHelper(), OSCAMRImageHelper())
        /**
         * Fix for the OutSystems NativeShell
         * The com.outsystems.myapp.BuildConfig class from BuildHelper.getBuildConfigValue is only created when using the cordova to build our app,
         * since we do not use cordova to build our app, we must add this condition to ensure that the applicationId is not null.
         * TODO: Remove this condition when we start to use cordova build command to build our applications.
         */
        if (applicationId == null) applicationId = cordova.activity.packageName
        if (action == "takePicture") {
            srcType = CAMERA
            destType = FILE_URI
            saveToPhotoAlbum = false
            targetHeight = 0
            targetWidth = 0
            encodingType = JPEG
            mediaType = PICTURE
            mQuality = 50

            //Take the values from the arguments if they're not already defined (this is tricky)
            destType = args.getInt(1)
            srcType = args.getInt(2)
            mQuality = args.getInt(0)
            targetWidth = args.getInt(3)
            targetHeight = args.getInt(4)
            encodingType = args.getInt(5)
            mediaType = args.getInt(6)
            allowEdit = args.getBoolean(7)
            correctOrientation = args.getBoolean(8)
            saveToPhotoAlbum = args.getBoolean(9)

            // If the user specifies a 0 or smaller width/height
            // make it -1 so later comparisons succeed
            if (targetWidth < 1) {
                targetWidth = -1
            }
            if (targetHeight < 1) {
                targetHeight = -1
            }

            // We don't return full-quality PNG files. The camera outputs a JPEG
            // so requesting it as a PNG provides no actual benefit
            if (targetHeight == -1 && targetWidth == -1 && mQuality == 100 &&
                !correctOrientation && encodingType == PNG && srcType == CAMERA
            ) {
                encodingType = JPEG
            }

            //create CameraParameters
            camParameters = OSCAMRParameters(
                mQuality,
                targetWidth,
                targetHeight,
                encodingType,
                mediaType,
                allowEdit,
                correctOrientation,
                saveToPhotoAlbum
            )

            try {
                if (srcType == CAMERA) {
                    callTakePicture(destType, encodingType)
                } else if (srcType == PHOTOLIBRARY || srcType == SAVEDPHOTOALBUM) {
                    callGetImage(srcType, destType, encodingType)
                }
            } catch (e: IllegalArgumentException) {
                callbackContext.error("Illegal Argument Exception")
                val r = PluginResult(PluginResult.Status.ERROR)
                callbackContext.sendPluginResult(r)
                return true
            }
            val r = PluginResult(PluginResult.Status.NO_RESULT)
            r.keepCallback = true
            callbackContext.sendPluginResult(r)
            return true
        }
        else if (action == "editPicture") {
            callEditImage(args)
            return true
        }
        return false
    }// Create the cache directory if it doesn't exist

    //--------------------------------------------------------------------------
    // LOCAL METHODS
    //--------------------------------------------------------------------------
    private val tempDirectoryPath: String
        private get() {
            val cache = cordova.activity.cacheDir
            // Create the cache directory if it doesn't exist
            cache.mkdirs()
            return cache.absolutePath
        }

    /**
     * Take a picture with the camera.
     * When an image is captured or the camera view is cancelled, the result is returned
     * in CordovaActivity.onActivityResult, which forwards the result to this.onActivityResult.
     *
     * The image can either be returned as a base64 string or a URI that points to the file.
     * To display base64 string in an img tag, set the source to:
     * img.src="data:image/jpeg;base64,"+result;
     * or to display URI in an img tag
     * img.src=result;
     *
     * @param returnType        Set the type of image to return.
     * @param encodingType           Compression quality hint (0-100: 0=low quality & high compression, 100=compress of max quality)
     */
    fun callTakePicture(returnType: Int, encodingType: Int) {
        val saveAlbumPermission = Build.VERSION.SDK_INT < 33 &&
                PermissionHelper.hasPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) &&
                PermissionHelper.hasPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ||
                Build.VERSION.SDK_INT >= 33 &&
                PermissionHelper.hasPermission(this, READ_MEDIA_VIDEO) &&
                PermissionHelper.hasPermission(this, READ_MEDIA_IMAGES)
        var takePicturePermission = PermissionHelper.hasPermission(this, Manifest.permission.CAMERA)

        // CB-10120: The CAMERA permission does not need to be requested unless it is declared
        // in AndroidManifest.xml. This plugin does not declare it, but others may and so we must
        // check the package info to determine if the permission is present.
        if (!takePicturePermission) {
            takePicturePermission = true
            try {
                val packageManager = cordova.activity.packageManager
                val permissionsInPackage = packageManager.getPackageInfo(
                    cordova.activity.packageName,
                    PackageManager.GET_PERMISSIONS
                ).requestedPermissions
                if (permissionsInPackage != null) {
                    for (permission in permissionsInPackage) {
                        if (permission == Manifest.permission.CAMERA) {
                            takePicturePermission = false
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(LOG_TAG, e.message.toString())
            }
        }
        if (takePicturePermission && saveAlbumPermission) {
            cordova.setActivityResultCallback(this)
            camController?.takePicture(cordova.activity, returnType, encodingType)
        } else if (saveAlbumPermission && !takePicturePermission) {
            PermissionHelper.requestPermission(this, TAKE_PIC_SEC, Manifest.permission.CAMERA)
        } else if (!saveAlbumPermission && takePicturePermission && Build.VERSION.SDK_INT < 33) {
            PermissionHelper.requestPermissions(
                this,
                TAKE_PIC_SEC,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        } else if (!saveAlbumPermission && takePicturePermission && Build.VERSION.SDK_INT >= 33) {
            PermissionHelper.requestPermissions(
                this,
                TAKE_PIC_SEC,
                arrayOf(READ_MEDIA_VIDEO, READ_MEDIA_IMAGES)
            )
        } else {
            PermissionHelper.requestPermissions(this, TAKE_PIC_SEC, permissions)
        }
    }

    /**
     * Get image from photo library.
     *
     * @param srcType           The album to get image from.
     * @param returnType        Set the type of image to return.
     * @param encodingType
     */
    fun callGetImage(srcType: Int, returnType: Int, encodingType: Int) {

        if (Build.VERSION.SDK_INT < 33 && !PermissionHelper.hasPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        ) {
            PermissionHelper.requestPermission(
                this,
                SAVE_TO_ALBUM_SEC,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        } else if (Build.VERSION.SDK_INT >= 33 && (!PermissionHelper.hasPermission(
                this,
                READ_MEDIA_IMAGES
            ) || !PermissionHelper.hasPermission(this, READ_MEDIA_VIDEO))
        ) {
            PermissionHelper.requestPermissions(
                this, SAVE_TO_ALBUM_SEC, arrayOf(
                    READ_MEDIA_VIDEO, READ_MEDIA_IMAGES
                )
            )
        } else {
            camParameters?.let {
                cordova.setActivityResultCallback(this)
                //camController?.getImage(this.cordova.activity, srcType, returnType, it)
            }
        }
    }

    fun callEditImage(args: JSONArray) {
        val imageBase64 = args.getString(0)
        cordova.setActivityResultCallback(this)
        camController?.editImage(cordova.activity, imageBase64, null, null)
    }

    private fun getCompressFormatForEncodingType(encodingType: Int): Bitmap.CompressFormat {
        return if (encodingType == JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
    }

    /**
     * Called when the camera view exits.
     *
     * @param requestCode The request code originally supplied to startActivityForResult(),
     * allowing you to identify who this result came from.
     * @param resultCode  The integer result code returned by the child activity through its setResult().
     * @param intent      An Intent, which can return result data to the caller (various data can be attached to Intent "extras").
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {

        // Get src and dest types from request code for a Camera Activity
        val srcType = requestCode / 16 - 1
        var destType = requestCode % 16 - 1
        if (requestCode == CROP_GALERY) {
            if (resultCode == Activity.RESULT_OK) {
                val result = BitmapFactory.decodeFile(croppedFilePath)
                val byteArrayOutputStream = ByteArrayOutputStream()
                if (result.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)) {
                    val byteArray = byteArrayOutputStream.toByteArray()
                    val base64Result = Base64.encodeToString(byteArray, Base64.NO_WRAP)
                    callbackContext?.success(base64Result)
                }
            } else if (resultCode == Activity.RESULT_CANCELED) {
                sendError(OSCAMRError.NO_IMAGE_SELECTED_ERROR)
            } else {
                sendError(OSCAMRError.EDIT_IMAGE_ERROR)
            }
        } else if (requestCode >= CROP_CAMERA) {
            if (resultCode == Activity.RESULT_OK) {

                // Because of the inability to pass through multiple intents, this hack will allow us
                // to pass arcane codes back.
                destType = requestCode - CROP_CAMERA
                try {
                    //processResultFromCamera(destType, intent)
                    camParameters?.let { it ->
                        camController?.processResultFromCamera(
                            cordova.activity,
                            destType,
                            intent,
                            it,
                            { image ->
                                val pluginResult = PluginResult(PluginResult.Status.OK, image)
                                this.callbackContext?.sendPluginResult(pluginResult)
                            },
                            { error ->
                                sendError(error)
                            }
                        )
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                    LOG.e(LOG_TAG, "Unable to write to file")
                }
            } // If cancelled
            else if (resultCode == Activity.RESULT_CANCELED) {
                sendError(OSCAMRError.NO_PICTURE_TAKEN_ERROR)
            } else {
                sendError(OSCAMRError.EDIT_IMAGE_ERROR)
            }
        } else if (srcType == CAMERA) {
            // If image available
            if (resultCode == Activity.RESULT_OK) {
                try {
                    if (allowEdit && camController != null) {
                        val tmpFile = FileProvider.getUriForFile(
                            cordova.activity,
                            "$applicationId.camera.provider",
                            camController!!.createCaptureFile(cordova.activity, encodingType)
                        )
                        cordova.setActivityResultCallback(this)
                        camController?.openCropActivity(cordova.activity, tmpFile, CROP_CAMERA, destType)
                    } else {
                        camParameters?.let { params ->
                            camController?.processResultFromCamera(
                                cordova.activity,
                                destType,
                                intent,
                                params,
                                {
                                    val pluginResult = PluginResult(PluginResult.Status.OK, it)
                                    this.callbackContext?.sendPluginResult(pluginResult)
                                },
                                {
                                    val pluginResult =
                                        PluginResult(PluginResult.Status.ERROR, it.toString())
                                    this.callbackContext?.sendPluginResult(pluginResult)
                                }
                            )
                        }
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                    sendError(OSCAMRError.TAKE_PHOTO_ERROR)
                }
            } else if (resultCode == Activity.RESULT_CANCELED) {
                sendError(OSCAMRError.NO_PICTURE_TAKEN_ERROR)
            } else {
                sendError(OSCAMRError.TAKE_PHOTO_ERROR)
            }
        } else if (srcType == PHOTOLIBRARY || srcType == SAVEDPHOTOALBUM) {
            if (resultCode == Activity.RESULT_OK && intent != null) {
                val finalDestType = destType
                if (allowEdit) {
                    val uri = intent.data
                    camController?.openCropActivity(cordova.activity, uri, CROP_GALERY, destType)
                } else {
                    cordova.threadPool.execute {
                        camParameters?.let { params ->
                            /*
                            camController?.processResultFromGallery(
                                this.cordova.activity,
                                finalDestType,
                                intent,
                                params,
                                {
                                    val pluginResult = PluginResult(PluginResult.Status.OK, it)
                                    this.callbackContext?.sendPluginResult(pluginResult)
                                },
                                {
                                    val pluginResult = PluginResult(PluginResult.Status.ERROR, it.toString())
                                    this.callbackContext?.sendPluginResult(pluginResult)
                                })

                             */
                        }
                        //processResultFromGallery(finalDestType, intent)
                    }
                }
            } else if (resultCode == Activity.RESULT_CANCELED) {
                sendError(OSCAMRError.NO_IMAGE_SELECTED_ERROR)
            } else {
                sendError(OSCAMRError.GET_IMAGE_ERROR)
            }
        } else if (requestCode == EDIT_RESULT) {
            if (resultCode == Activity.RESULT_OK) {
                camController?.processResultFromEdit(intent,
                    {
                        val pluginResult = PluginResult(PluginResult.Status.OK, it)
                        this.callbackContext?.sendPluginResult(pluginResult)
                    },
                    {
                        sendError(it)
                    })
            }
            else if (resultCode == Activity.RESULT_CANCELED) {
                //sendError(OSCAMRError.EDIT_IMAGE_ERROR)
                //alterar isto depois para EDIT_CANCELLED_ERROR com o OSCAMRError
                val pluginResult =
                    PluginResult(PluginResult.Status.ERROR, OSCAMRError.EDIT_CANCELLED_ERROR.toString())
                this.callbackContext?.sendPluginResult(pluginResult)
            }
            else {
                sendError(OSCAMRError.EDIT_IMAGE_ERROR)
            }
        } else if (requestCode == RECOVERABLE_DELETE_REQUEST) {
            // retry media store deletion ...
            val contentResolver = cordova.activity.contentResolver
            try {
                pendingDeleteMediaUri?.let { contentResolver.delete(it, null, null) }
            } catch (e: Exception) {
                LOG.e(LOG_TAG, "Unable to delete media store file after permission was granted")
            }
            pendingDeleteMediaUri = null
        }
    }

    /**
     * Creates a cursor that can be used to determine how many images we have.
     *
     * @return a cursor
     */
    private fun queryImgDB(contentStore: Uri): Cursor? {
        return cordova.activity.contentResolver.query(
            contentStore, arrayOf(MediaStore.Images.Media._ID),
            null,
            null,
            null
        )
    }

    override fun onRequestPermissionResult(
        requestCode: Int, permissions: Array<String>,
        grantResults: IntArray
    ) {
        for (i in grantResults.indices) {
            if (grantResults[i] == PackageManager.PERMISSION_DENIED && permissions[i] == Manifest.permission.CAMERA) {
                sendError(OSCAMRError.CAMERA_PERMISSION_DENIED_ERROR)
                return
            } else if (grantResults[i] == PackageManager.PERMISSION_DENIED && ((Build.VERSION.SDK_INT < 33
                        && (permissions[i] == Manifest.permission.READ_EXTERNAL_STORAGE || permissions[i] == Manifest.permission.WRITE_EXTERNAL_STORAGE))
                        || (Build.VERSION.SDK_INT >= 33
                        && (permissions[i] == READ_MEDIA_IMAGES || permissions[i] == READ_MEDIA_VIDEO)))
            ) {
                sendError(OSCAMRError.GALLERY_PERMISSION_DENIED_ERROR)
                return
            }
        }
        when (requestCode) {
            TAKE_PIC_SEC -> {
                cordova.setActivityResultCallback(this)
                camController?.takePicture(this.cordova.activity, destType, encodingType)
            }
            SAVE_TO_ALBUM_SEC -> callGetImage(srcType, destType, encodingType)
        }
    }

    /**
     * Taking or choosing a picture launches another Activity, so we need to implement the
     * save/restore APIs to handle the case where the CordovaActivity is killed by the OS
     * before we get the launched Activity's result.
     */
    override fun onSaveInstanceState(): Bundle {
        val state = Bundle()
        state.putInt("destType", destType)
        state.putInt("srcType", srcType)
        state.putInt("mQuality", mQuality)
        state.putInt("targetWidth", targetWidth)
        state.putInt("targetHeight", targetHeight)
        state.putInt("encodingType", encodingType)
        state.putInt("mediaType", mediaType)
        state.putInt("numPics", numPics)
        state.putBoolean("allowEdit", allowEdit)
        state.putBoolean("correctOrientation", correctOrientation)
        state.putBoolean("saveToPhotoAlbum", saveToPhotoAlbum)
        if (croppedUri != null) {
            state.putString(CROPPED_URI_KEY, croppedFilePath)
        }
        if (imageUri != null) {
            state.putString(IMAGE_URI_KEY, imageFilePath)
        }
        if (imageFilePath != null) {
            state.putString(IMAGE_FILE_PATH_KEY, imageFilePath)
        }
        return state
    }

    override fun onRestoreStateForActivityResult(state: Bundle, callbackContext: CallbackContext) {
        destType = state.getInt("destType")
        srcType = state.getInt("srcType")
        mQuality = state.getInt("mQuality")
        targetWidth = state.getInt("targetWidth")
        targetHeight = state.getInt("targetHeight")
        encodingType = state.getInt("encodingType")
        mediaType = state.getInt("mediaType")
        numPics = state.getInt("numPics")
        allowEdit = state.getBoolean("allowEdit")
        correctOrientation = state.getBoolean("correctOrientation")
        saveToPhotoAlbum = state.getBoolean("saveToPhotoAlbum")
        if (state.containsKey(CROPPED_URI_KEY)) {
            croppedUri = Uri.parse(state.getString(CROPPED_URI_KEY))
        }
        if (state.containsKey(IMAGE_URI_KEY)) {
            //I have no idea what type of URI is being passed in
            imageUri = Uri.parse(state.getString(IMAGE_URI_KEY))
        }
        if (state.containsKey(IMAGE_FILE_PATH_KEY)) {
            imageFilePath = state.getString(IMAGE_FILE_PATH_KEY)
        }
        this.callbackContext = callbackContext
    }

    private fun sendError(error: OSCAMRError) {
        val jsonResult = JSONObject()
        try {
            jsonResult.put("code", formatErrorCode(error.code))
            jsonResult.put("message", error.description)
            callbackContext?.error(jsonResult)
        } catch (e: JSONException) {
            LOG.d(LOG_TAG, "Error: JSONException occurred while preparing to send an error.")
            callbackContext?.error("There was an error performing the operation.")
        }
    }

    private fun formatErrorCode(code: Int): String {
        val stringCode = Integer.toString(code)
        return ERROR_FORMAT_PREFIX + "0000$stringCode".substring(stringCode.length)
    }

    companion object {
        private const val DATA_URL = 0 // Return base64 encoded string
        private const val FILE_URI =
            1 // Return file uri (content://media/external/images/media/2 for Android)
        private const val NATIVE_URI = 2 // On Android, this is the same as FILE_URI
        private const val PHOTOLIBRARY =
            0 // Choose image from picture library (same as SAVEDPHOTOALBUM for Android)
        private const val CAMERA = 1 // Take picture from camera
        private const val SAVEDPHOTOALBUM =
            2 // Choose image from picture library (same as PHOTOLIBRARY for Android)
        private const val RECOVERABLE_DELETE_REQUEST = 3 // Result of Recoverable Security Exception
        private const val PICTURE =
            0 // allow selection of still pictures only. DEFAULT. Will return format specified via DestinationType
        private const val VIDEO = 1 // allow selection of video only, ONLY RETURNS URL
        private const val ALLMEDIA = 2 // allow selection from all media types
        private const val JPEG = 0 // Take a picture of type JPEG
        private const val PNG = 1 // Take a picture of type PNG
        private const val JPEG_TYPE = "jpg"
        private const val PNG_TYPE = "png"
        private const val JPEG_EXTENSION = "." + JPEG_TYPE
        private const val PNG_EXTENSION = "." + PNG_TYPE
        private const val PNG_MIME_TYPE = "image/png"
        private const val JPEG_MIME_TYPE = "image/jpeg"
        private const val GET_PICTURE = "Get Picture"
        private const val GET_VIDEO = "Get Video"
        private const val GET_All = "Get All"
        private const val CROPPED_URI_KEY = "croppedUri"
        private const val IMAGE_URI_KEY = "imageUri"
        private const val IMAGE_FILE_PATH_KEY = "imageFilePath"
        private const val TAKE_PICTURE_ACTION = "takePicture"
        const val TAKE_PIC_SEC = 0
        const val SAVE_TO_ALBUM_SEC = 1
        private const val LOG_TAG = "CameraLauncher"

        //Where did this come from?
        private const val CROP_CAMERA = 100
        private const val CROP_GALERY = 666
        private const val TIME_FORMAT = "yyyyMMdd_HHmmss"

        //we need literal values because we cannot simply do Manifest.permission.READ_MEDIA_IMAGES, because of the target sdk
        private const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
        private const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"

        private const val EDIT_RESULT = 7

        //for errors
        private const val ERROR_FORMAT_PREFIX = "OS-PLUG-CAMR-"
        protected val permissions = createPermissionArray()

        private fun createPermissionArray(): Array<String> {
            return if (Build.VERSION.SDK_INT < 33) {
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            } else {
                arrayOf(
                    Manifest.permission.CAMERA,
                    READ_MEDIA_IMAGES,
                    READ_MEDIA_VIDEO
                )
            }
        }
    }
}