package com.jr.arcorecodelab

import android.media.Image
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.sceneform.ux.ArFragment
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private val TAG = "ARTest"
    lateinit var arFragment: ArFragment
    private var isTracking: Boolean = false
    private var lastPose: Pose? = null
    private var cameraByteBuffer = ByteBuffer.allocateDirect(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        arFragment = supportFragmentManager.findFragmentById(R.id.sceneform_fragment) as ArFragment

        arFragment.arSceneView.scene.addOnUpdateListener {
            arFragment.onUpdate(it)
            onUpdate()
        }

    }

    private fun onUpdate(): Boolean {
        val frame = arFragment.arSceneView.arFrame
        val wasTracking = isTracking
        frame ?: return this.isTracking != wasTracking
        isTracking = frame.camera.trackingState == TrackingState.TRACKING
        // measure distance between current camera pose and last camera pose
        measureDistance(this.lastPose ?: frame.androidSensorPose, frame.androidSensorPose)
        getCameraFrame(frame)
        Log.i(TAG, "+++++ timesetamp="+frame.androidCameraTimestamp)
        this.lastPose = frame.androidSensorPose
        return isTracking != wasTracking
    }

    private fun getCameraFrame(frame: Frame) {
        val image: Image
        try {
            image = frame.acquireCameraImage() as Image
        } catch (e: NotYetAvailableException) {
            Log.w(TAG, "+++++ not yet available camera frame")
            return
        }
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val luminance = yPlane.buffer
        val chromaU = uPlane.buffer
        val lumSize = luminance.remaining()
        val uSize = chromaU.remaining()
        val imageSize = lumSize + uSize
        this.cameraByteBuffer.clear()
        if (this.cameraByteBuffer.remaining() < imageSize) {
            this.cameraByteBuffer = ByteBuffer.allocateDirect(imageSize)
        }
        this.cameraByteBuffer.put(luminance)
        this.cameraByteBuffer.put(chromaU)
        this.cameraByteBuffer.flip()

        image.close()
    }

    private fun measureDistance(startPose: Pose, endPose: Pose) {
        val dx = startPose.tx() - endPose.tx()
        val dy = startPose.ty() - endPose.ty()
        val dz = startPose.tz() - endPose.tz()
        val distanceMeters = Math.sqrt((dx*dx + dy*dy + dz*dz).toDouble())
        Log.i(TAG, "+++++ distance = $distanceMeters[m]")
    }

}
