package com.exyon.itraffic

import android.content.Context
import android.graphics.*
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

class YoloHelper(context: Context, modelPath: String, labelPath: String) {
    private val tflite: Interpreter
    private val labels: List<String>
    private val INPUT_SIZE = 640
    private val NUM_BOXES = 25200
    private val NUM_CLASSES = 6
    private val SCORE_THRESHOLD = 0.3f
    private val IOU_THRESHOLD = 0.5f

    companion object {
        val lastDetectedClasses = mutableListOf<String>()
    }

    init {
        val modelBuffer = FileUtil.loadMappedFile(context, modelPath)
        tflite = Interpreter(modelBuffer)
        labels = FileUtil.loadLabels(context, labelPath)

        val inputShape = tflite.getInputTensor(0).shape()
        val inputType = tflite.getInputTensor(0).dataType()
        Log.d("YoloHelper", "Model input shape: ${inputShape.contentToString()}, Type: $inputType")
    }

    fun detect(bitmap: Bitmap): Bitmap {
        val inputBuffer = convertBitmapToByteBuffer(bitmap)
        val output = Array(1) { Array(NUM_BOXES) { FloatArray(NUM_CLASSES + 5) } }

        tflite.run(inputBuffer, output)

        val detections = decodeOutput(output[0])
        val nmsDetections = nonMaxSuppression(detections)

        lastDetectedClasses.clear()
        for (det in nmsDetections) {
            val label = labels[det.classId]
            Log.d("YoloHelper", "Detected: $label with confidence: ${det.score}")
            lastDetectedClasses.add(label)
        }

        return drawDetections(bitmap, nmsDetections)
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val buffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())

        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val pixel = resized.getPixel(x, y)
                buffer.putFloat(((pixel shr 16 and 0xFF) / 255.0f))
                buffer.putFloat(((pixel shr 8 and 0xFF) / 255.0f))
                buffer.putFloat((pixel and 0xFF) / 255.0f)
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun decodeOutput(output: Array<FloatArray>): List<Detection> {
        val detections = mutableListOf<Detection>()

        for (i in 0 until NUM_BOXES) {
            val confidence = output[i][4]
            if (confidence < SCORE_THRESHOLD) continue

            var maxClassScore = 0f
            var classId = -1
            for (c in 0 until NUM_CLASSES) {
                val classScore = output[i][5 + c]
                if (classScore > maxClassScore) {
                    maxClassScore = classScore
                    classId = c
                }
            }

            val finalScore = confidence * maxClassScore
            if (finalScore < SCORE_THRESHOLD) continue

            val cx = output[i][0]
            val cy = output[i][1]
            val w = output[i][2]
            val h = output[i][3]

            val left = cx - w / 2
            val top = cy - h / 2
            val right = cx + w / 2
            val bottom = cy + h / 2

            detections.add(Detection(left, top, right, bottom, finalScore, classId))
        }

        return detections
    }

    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        val sorted = detections.sortedByDescending { it.score }.toMutableList()
        val result = mutableListOf<Detection>()
        val removed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (removed[i]) continue
            val detA = sorted[i]
            result.add(detA)

            for (j in i + 1 until sorted.size) {
                if (removed[j]) continue
                if (iou(detA, sorted[j]) > IOU_THRESHOLD) {
                    removed[j] = true
                }
            }
        }

        return result
    }

    private fun iou(a: Detection, b: Detection): Float {
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)

        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        val interArea = maxOf(0f, interRight - interLeft) * maxOf(0f, interBottom - interTop)
        return interArea / (areaA + areaB - interArea)
    }

    private fun drawDetections(bitmap: Bitmap, detections: List<Detection>): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        val boxPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 40f
            style = Paint.Style.FILL
        }

        val scaleX = bitmap.width.toFloat() / INPUT_SIZE
        val scaleY = bitmap.height.toFloat() / INPUT_SIZE

        for (det in detections) {
            val left = det.left * scaleX
            val top = det.top * scaleY
            val right = det.right * scaleX
            val bottom = det.bottom * scaleY

            canvas.drawRect(left, top, right, bottom, boxPaint)
            val label = "${labels[det.classId]} %.2f".format(det.score)
            canvas.drawText(label, left, top - 10f, textPaint)
        }

        return mutableBitmap
    }

    data class Detection(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val score: Float,
        val classId: Int
    )
}
