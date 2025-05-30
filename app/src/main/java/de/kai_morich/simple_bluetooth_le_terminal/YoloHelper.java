package de.kai_morich.simple_bluetooth_le_terminal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class YoloHelper {

    private Interpreter tflite;
    private List<String> labels;
    private final int INPUT_SIZE = 640;
    private final int NUM_BOXES = 25200;  // YOLOv5 640 모델 기준
    private final int NUM_CLASSES = 6;
    private final float SCORE_THRESHOLD = 0.3f;
    private final float IOU_THRESHOLD = 0.5f;

    private List<String> lastDetectedClasses = new ArrayList<>();

    public List<String> getLastDetectedClasses() {
        return lastDetectedClasses;
    }

    public YoloHelper(Context context, String modelPath, String labelPath) throws IOException {
        // TFLite 모델 로드
        ByteBuffer modelBuffer = FileUtil.loadMappedFile(context, modelPath);
        tflite = new Interpreter(modelBuffer);

        // 클래스 레이블 읽기
        labels = FileUtil.loadLabels(context, labelPath);

        // 디버깅용 입력 정보 출력
        int[] inputShape = tflite.getInputTensor(0).shape();
        DataType inputType = tflite.getInputTensor(0).dataType();
    }

    // 메인 감지 함수: 입력 비트맵 → 탐지 결과 비트맵 반환
    public Bitmap detect(Bitmap bitmap) {
        ByteBuffer inputBuffer = convertBitmapToByteBuffer(bitmap);

        float[][][] output = new float[1][NUM_BOXES][NUM_CLASSES + 5];
        tflite.run(inputBuffer, output);

        List<Detection> detections = decodeOutput(output[0]);
        List<Detection> nmsDetections = nonMaxSuppression(detections);

        // 감지 결과 로그 찍기
        if (nmsDetections.isEmpty()) {
        } else {
            for (Detection det : nmsDetections) {
                String label = labels.get(det.classId);
                Log.d("YoloHelper", "Detected: " + label + " with confidence: " + det.score);
                lastDetectedClasses.add(label);
            }
        }

        // 박스 없이 원본 비트맵 반환 (박스 그리기 생략)
        return bitmap;
    }

    // Bitmap → ByteBuffer 변환 (FLOAT32, 640x640, RGB 0~1 정규화)
    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);

        ByteBuffer buffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4);
        buffer.order(ByteOrder.nativeOrder());

        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                int pixel = resized.getPixel(x, y);

                float r = ((pixel >> 16) & 0xFF) / 255.f;
                float g = ((pixel >> 8) & 0xFF) / 255.f;
                float b = (pixel & 0xFF) / 255.f;

                buffer.putFloat(r);
                buffer.putFloat(g);
                buffer.putFloat(b);
            }
        }

        buffer.rewind();
        return buffer;
    }

    // 모델 출력 배열을 객체 정보 리스트로 변환
    private List<Detection> decodeOutput(float[][] output) {
        List<Detection> detections = new ArrayList<>();

        lastDetectedClasses.clear();
        for (int i = 0; i < NUM_BOXES; i++) {
            float confidence = output[i][4];
            if (confidence < SCORE_THRESHOLD) continue;

            // 클래스별 점수 최대값과 인덱스 찾기
            float maxClassScore = 0;
            int classId = -1;
            for (int c = 0; c < NUM_CLASSES; c++) {
                float classScore = output[i][5 + c];
                if (classScore > maxClassScore) {
                    maxClassScore = classScore;
                    classId = c;
                }
            }

            float finalScore = confidence * maxClassScore;
            if (finalScore < SCORE_THRESHOLD) continue;

            // 박스 좌표 (YOLOv5는 cx, cy, w, h 형식)
            float cx = output[i][0];
            float cy = output[i][1];
            float w = output[i][2];
            float h = output[i][3];

            float left = cx - w / 2;
            float top = cy - h / 2;
            float right = cx + w / 2;
            float bottom = cy + h / 2;

            detections.add(new Detection(left, top, right, bottom, finalScore, classId));
        }

        return detections;
    }

    // Non-Maximum Suppression (NMS) - 박스 중복 제거
    private List<Detection> nonMaxSuppression(List<Detection> detections) {
        List<Detection> nmsList = new ArrayList<>();

        // 점수 내림차순 정렬
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            detections.sort((d1, d2) -> Float.compare(d2.score, d1.score));
        }

        boolean[] removed = new boolean[detections.size()];

        for (int i = 0; i < detections.size(); i++) {
            if (removed[i]) continue;
            Detection detA = detections.get(i);
            nmsList.add(detA);

            for (int j = i + 1; j < detections.size(); j++) {
                if (removed[j]) continue;
                Detection detB = detections.get(j);

                if (iou(detA, detB) > IOU_THRESHOLD) {
                    removed[j] = true;
                }
            }
        }

        return nmsList;
    }

    // IoU 계산
    private float iou(Detection a, Detection b) {
        float areaA = (a.right - a.left) * (a.bottom - a.top);
        float areaB = (b.right - b.left) * (b.bottom - b.top);

        float interLeft = Math.max(a.left, b.left);
        float interTop = Math.max(a.top, b.top);
        float interRight = Math.min(a.right, b.right);
        float interBottom = Math.min(a.bottom, b.bottom);

        float interArea = Math.max(0, interRight - interLeft) * Math.max(0, interBottom - interTop);
        return interArea / (areaA + areaB - interArea);
    }

    // 탐지 결과를 원본 Bitmap에 그리기
    private Bitmap drawDetections(Bitmap bitmap, List<Detection> detections) {
        Bitmap mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(mutableBitmap);

        Paint boxPaint = new Paint();
        boxPaint.setColor(Color.RED);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4);

        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(40);
        textPaint.setStyle(Paint.Style.FILL);

        for (Detection det : detections) {
            // 좌표를 원본 크기에 맞게 조정 (모델 출력은 0~640 비율)
            float scaleX = (float) bitmap.getWidth() / INPUT_SIZE;
            float scaleY = (float) bitmap.getHeight() / INPUT_SIZE;

            float left = det.left * scaleX;
            float top = det.top * scaleY;
            float right = det.right * scaleX;
            float bottom = det.bottom * scaleY;

            canvas.drawRect(left, top, right, bottom, boxPaint);

            String label = labels.get(det.classId) + String.format(" %.2f", det.score);
            canvas.drawText(label, left, top - 10, textPaint);
        }

        return mutableBitmap;
    }

    // 탐지 결과 클래스
    private static class Detection {
        float left, top, right, bottom, score;
        int classId;

        Detection(float left, float top, float right, float bottom, float score, int classId) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.score = score;
            this.classId = classId;
        }
    }

}
