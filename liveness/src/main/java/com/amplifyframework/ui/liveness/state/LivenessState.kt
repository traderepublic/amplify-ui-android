/*
 * Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amplifyframework.ui.liveness.state

import android.content.Context
import android.graphics.RectF
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.amplifyframework.predictions.aws.models.ColorChallenge
import com.amplifyframework.predictions.aws.models.ColorChallengeType
import com.amplifyframework.predictions.aws.models.FaceTargetChallenge
import com.amplifyframework.predictions.aws.models.FaceTargetChallengeResponse
import com.amplifyframework.predictions.aws.models.InitialFaceDetected
import com.amplifyframework.predictions.models.FaceLivenessSession
import com.amplifyframework.predictions.models.VideoEvent
import com.amplifyframework.ui.liveness.camera.LivenessCoordinator
import com.amplifyframework.ui.liveness.ml.FaceDetector
import com.amplifyframework.ui.liveness.ml.FaceOval
import com.amplifyframework.ui.liveness.model.FaceLivenessDetectionException
import com.amplifyframework.ui.liveness.model.LivenessCheckState
import com.amplifyframework.ui.liveness.ui.helper.VideoViewportSize
import com.amplifyframework.ui.liveness.util.WebSocketCloseCode
import java.util.Date
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.schedule

internal data class InitialStreamFace(val faceRect: RectF, val timestamp: Long)

data class LivenessState(
    val sessionId: String,
    val context: Context,
    val disableStartView: Boolean,
    val onCaptureReady: () -> Unit,
    val onFaceDistanceCheckPassed: () -> Unit,
    val onSessionError: (FaceLivenessDetectionException, Boolean) -> Unit,
    val onFinalEventsSent: () -> Unit,
) {
    var videoViewportSize: VideoViewportSize? by mutableStateOf(null)
    var livenessCheckState = mutableStateOf<LivenessCheckState>(
        LivenessCheckState.Initial()
    )
    var runningFreshness by mutableStateOf(false)
    var faceGuideRect: RectF? by mutableStateOf(null)
    var faceMatchPercentage: Float by mutableStateOf(0.25f)
    var initialFaceDistanceCheckPassed by mutableStateOf(false)
    var initialLocalFaceFound by mutableStateOf(false)

    var showingStartView by mutableStateOf(!disableStartView)

    private var initialStreamFace: InitialStreamFace? = null
    @VisibleForTesting
    var faceMatchOvalStart: Long? = null
    @VisibleForTesting
    var faceMatchOvalEnd: Long? = null
    private var initialFaceOvalIou = -1f
    private var faceOvalMatchTimer: TimerTask? = null
    private var detectedFaceMatchedOval = false

    @VisibleForTesting
    var readyForOval = false

    @VisibleForTesting
    var readyToSendFinalEvents = false

    var livenessSessionInfo: FaceLivenessSession? = null
    var faceTargetChallenge: FaceTargetChallenge? by mutableStateOf(null)
    var colorChallenge: ColorChallenge? = null

    fun updateVideoViewportSize(newVideoViewportSize: VideoViewportSize) {
        if (newVideoViewportSize != videoViewportSize) {
            videoViewportSize = newVideoViewportSize
        }
    }

    fun onError(stopLivenessSession: Boolean, webSocketCloseCode: WebSocketCloseCode) {
        livenessCheckState.value = LivenessCheckState.Error
        onDestroy(stopLivenessSession, webSocketCloseCode)
    }

    // Cleans up state when challenge is completed or cancelled.
    // We only send webSocketCloseCode if error encountered.
    fun onDestroy(stopLivenessSession: Boolean, webSocketCloseCode: WebSocketCloseCode? = null) {
        livenessCheckState.value = LivenessCheckState.Error
        faceOvalMatchTimer?.cancel()
        readyForOval = false
        faceGuideRect = null
        runningFreshness = false
        if (stopLivenessSession) {
            livenessSessionInfo?.stopSession(webSocketCloseCode?.code)
        }
    }

    fun onLivenessSessionReady(faceLivenessSession: FaceLivenessSession) {
        livenessSessionInfo = faceLivenessSession
        faceTargetChallenge = faceLivenessSession.challenges
            .filterIsInstance<FaceTargetChallenge>().firstOrNull()
        colorChallenge = faceLivenessSession.challenges
            .filterIsInstance<ColorChallenge>().firstOrNull()
        livenessCheckState.value = LivenessCheckState.Running()
        readyForOval = true
    }

    fun onFullChallengeComplete() {
        readyToSendFinalEvents = true
    }

    fun onFreshnessComplete() {
        val faceGuideRect = this.faceGuideRect
        readyForOval = false
        this.faceGuideRect = null
        runningFreshness = false
        if (faceMatchOvalEnd == null) {
            faceMatchOvalEnd = Date().time
        }

        livenessCheckState.value = if (faceGuideRect != null) {
            LivenessCheckState.Success(faceGuideRect)
        } else {
            LivenessCheckState.Error
        }
    }

    /**
     * @return true if FrameAnalyzer should continue processing the frame
     */
    fun onFrameAvailable(): Boolean {
        if (showingStartView) return false

        return when (val livenessCheckState = livenessCheckState.value) {
            is LivenessCheckState.Error -> false
            is LivenessCheckState.Initial, is LivenessCheckState.Running -> {
                /**
                 * Start freshness check if the face has matched oval (we know this if faceMatchOvalStart is not null)
                 * We trigger this in onFrameAvailable instead of onFrameFaceUpdate in the event the user moved the face
                 * away from the camera. We want to run this check on every frame if the challenge is in process.
                 */
                if (!runningFreshness && colorChallenge?.challengeType ==
                    ColorChallengeType.SEQUENTIAL &&
                    faceMatchOvalStart?.let { (Date().time - it) > 1000 } == true
                ) {
                    runningFreshness = true
                }
                true
            }
            is LivenessCheckState.Success -> {
                if (readyToSendFinalEvents) {
                    readyToSendFinalEvents = false

                    livenessSessionInfo!!.sendChallengeResponseEvent(
                        FaceTargetChallengeResponse(
                            colorChallenge!!.challengeId,
                            livenessCheckState.faceGuideRect,
                            Date(faceMatchOvalStart!!),
                            Date(faceMatchOvalEnd!!)
                        )
                    )

                    // Send empty video event to signal we're done sending video
                    livenessSessionInfo!!.sendVideoEvent(VideoEvent(ByteArray(0), Date()))
                    onFinalEventsSent()
                }
                false
            }
        }
    }

    fun onFrameFaceCountUpdate(faceCount: Int) {
        if (detectedFaceMatchedOval) {
            return
        }
        when (faceCount) {
            0 -> {
                if (!initialLocalFaceFound || livenessCheckState.value is LivenessCheckState.Initial) {
                    livenessCheckState.value = LivenessCheckState.Initial.withMoveFaceMessage()
                } else if (livenessCheckState.value is LivenessCheckState.Running) {
                    livenessCheckState.value = LivenessCheckState.Running.withMoveFaceMessage()
                }
            }
            1 -> {
                if (!initialLocalFaceFound) {
                    initialLocalFaceFound = true
                }
            }
            else -> {
                if (!initialLocalFaceFound || livenessCheckState.value is LivenessCheckState.Initial) {
                    livenessCheckState.value = LivenessCheckState.Initial.withMultipleFaceMessage()
                } else if (livenessCheckState.value is LivenessCheckState.Running) {
                    livenessCheckState.value = LivenessCheckState.Running.withMultipleFaceMessage()
                }
            }
        }
    }

    /**
     * returns true if face update inspect, false if thrown away
     */
    fun onFrameFaceUpdate(
        faceRect: RectF,
        leftEye: FaceDetector.Landmark,
        rightEye: FaceDetector.Landmark,
        mouth: FaceDetector.Landmark
    ): Boolean {
        if (showingStartView) {
            return false
        }

        if (!initialFaceDistanceCheckPassed) {
            val faceDistance = FaceDetector.calculateFaceDistance(
                leftEye, rightEye, mouth,
                LivenessCoordinator.TARGET_WIDTH, LivenessCoordinator.TARGET_HEIGHT
            )
            if (faceDistance >= FaceDetector.INITIAL_FACE_DISTANCE_THRESHOLD) {
                livenessCheckState.value =
                    LivenessCheckState.Initial.withMoveFaceFurtherAwayMessage()
            } else {
                initialFaceDistanceCheckPassed = true
                onFaceDistanceCheckPassed()
            }
        }

        if (readyForOval) {
            if (initialStreamFace == null) {
                val face = InitialStreamFace(faceRect, System.currentTimeMillis())
                onCaptureReady()
                livenessSessionInfo!!.sendChallengeResponseEvent(
                    InitialFaceDetected(
                        colorChallenge!!.challengeId,
                        face.faceRect,
                        Date(face.timestamp)
                    )
                )

                this.initialStreamFace = face
            }

            if (faceGuideRect == null) {
                faceGuideRect =
                    FaceOval.createBoundingRect(faceTargetChallenge!!)
            }
        }

        faceGuideRect?.let { oval ->

            val faceOvalPosition = FaceDetector.calculateFaceOvalPosition(
                faceRect,
                oval,
                faceTargetChallenge!!.faceTargetMatching
            )

            if (initialFaceOvalIou < 0) {
                initialFaceOvalIou = FaceDetector.intersectionOverUnion(faceRect, oval)
            }

            faceMatchPercentage = FaceDetector.calculateFaceMatchPercentage(
                faceRect,
                oval,
                faceTargetChallenge!!.faceTargetMatching,
                initialFaceOvalIou
            )

            detectedFaceMatchedOval = detectedFaceMatchedOval ||
                faceOvalPosition == FaceDetector.FaceOvalPosition.MATCHED

            if (detectedFaceMatchedOval) {
                livenessCheckState.value = LivenessCheckState.Running.withFaceOvalPosition(
                    FaceDetector.FaceOvalPosition.MATCHED
                )
            } else {
                livenessCheckState.value = LivenessCheckState.Running.withFaceOvalPosition(
                    faceOvalPosition
                )
            }

            if (detectedFaceMatchedOval && faceMatchOvalStart == null) {
                faceMatchOvalStart = Date().time
            } else if (!detectedFaceMatchedOval && faceMatchOvalStart != null &&
                faceMatchOvalEnd == null
            ) {
                faceMatchOvalEnd = Date().time
            }

            // Start timer and then timeout if the detected face doesn't match
            // the oval after a period of time
            if (!detectedFaceMatchedOval && faceOvalMatchTimer == null) {
                faceOvalMatchTimer =
                    Timer().schedule(faceTargetChallenge!!.faceTargetMatching.ovalFitTimeout.toLong()) {
                        if (!detectedFaceMatchedOval && faceGuideRect != null) {
                            readyForOval = false
                            val timeoutError =
                                FaceLivenessDetectionException.FaceInOvalMatchExceededTimeLimitException()
                            onSessionError(timeoutError, true)
                        }
                        cancel()
                    }
            }
        }
        return true
    }

    fun onStartViewComplete() {
        showingStartView = false
    }
}
