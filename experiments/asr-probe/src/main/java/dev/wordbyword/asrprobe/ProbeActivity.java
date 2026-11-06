package dev.wordbyword.asrprobe;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;
import android.widget.TextView;
import com.k2fsa.sherpa.onnx.*;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ProbeActivity extends Activity {
    private static final int RATE = 16000;
    private TextView status;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        status = new TextView(this);
        status.setPadding(24, 24, 24, 24);
        setContentView(status);
        File input = new File(getExternalFilesDir(null), "probe");
        if (!input.isDirectory() && !input.mkdirs()) {
            status.setText("Cannot create " + input);
            return;
        }
        status.setText("ASR probe ready. Input: " + input);
        if (getIntent().getBooleanExtra("run", false)) {
            String wave = getIntent().getStringExtra("wave");
            boolean full = getIntent().getBooleanExtra("full", true);
            int paddingMs = getIntent().getIntExtra("padding_ms", 0);
            new Thread(() -> runProbe(input, wave, full, paddingMs), "asr-probe").start();
        }
    }

    private void runProbe(File input, String wave, boolean full, int paddingMs) {
        JSONObject report = new JSONObject();
        try {
            if (wave == null || !wave.matches("[A-Za-z0-9_-]+\\.wav")) {
                throw new IllegalArgumentException("Expected simple WAV filename");
            }
            report.put("runtime", "sherpa-onnx 2.0.1 Android AAR, called from Java");
            report.put("model", "giga-am-v2-russian-2025-04-19 int8 encoder");
            report.put("device", Build.MODEL);
            report.put("abi", Build.SUPPORTED_ABIS[0]);
            report.put("sdk", Build.VERSION.SDK_INT);
            report.put("threads", 2);
            if (paddingMs < 0 || paddingMs > 1000) throw new IllegalArgumentException("Invalid padding");
            report.put("vad_padding_ms", paddingMs);
            report.put("wave", wave);
            report.put("run_id", getIntent().getStringExtra("run_id"));
            float[] samples = readWave(new File(input, wave));
            report.put("audio_seconds", samples.length / (double) RATE);
            File model = new File(input, "model");
            OfflineTransducerModelConfig transducer = new OfflineTransducerModelConfig();
            transducer.setEncoder(required(model, "encoder.int8.onnx"));
            transducer.setDecoder(required(model, "decoder.onnx"));
            transducer.setJoiner(required(model, "joiner.onnx"));
            OfflineModelConfig modelConfig = new OfflineModelConfig();
            modelConfig.setTransducer(transducer);
            modelConfig.setTokens(required(model, "tokens.txt"));
            modelConfig.setModelType("nemo_transducer");
            modelConfig.setNumThreads(2);
            modelConfig.setProvider("cpu");
            OfflineRecognizerConfig config = new OfflineRecognizerConfig();
            config.setModelConfig(modelConfig);
            long started = SystemClock.elapsedRealtime();
            OfflineRecognizer recognizer = new OfflineRecognizer(null, config);
            report.put("asr_load_ms", SystemClock.elapsedRealtime() - started);
            try {
                if (full) {
                    started = SystemClock.elapsedRealtime();
                    report.put("full", decode(recognizer, samples, 0, samples.length));
                    report.put("full_decode_ms", SystemClock.elapsedRealtime() - started);
                }
                SileroVadModelConfig silero = new SileroVadModelConfig();
                silero.setModel(required(input, "silero_vad.onnx"));
                silero.setMinSpeechDuration(0.25f);
                silero.setMinSilenceDuration(0.25f);
                silero.setMaxSpeechDuration(15.0f);
                VadModelConfig vadConfig = new VadModelConfig();
                vadConfig.setSileroVadModelConfig(silero);
                started = SystemClock.elapsedRealtime();
                Vad vad = new Vad(null, vadConfig);
                report.put("vad_load_ms", SystemClock.elapsedRealtime() - started);
                try {
                    started = SystemClock.elapsedRealtime();
                    List<SpeechSegment> speech = new ArrayList<>();
                    for (int i = 0; i < samples.length; i += 512) {
                        vad.acceptWaveform(Arrays.copyOfRange(samples, i, i + 512));
                        drain(vad, speech);
                    }
                    vad.flush();
                    drain(vad, speech);
                    report.put("vad_ms", SystemClock.elapsedRealtime() - started);
                    JSONArray segments = new JSONArray();
                    started = SystemClock.elapsedRealtime();
                    for (SpeechSegment segment : speech) {
                        int rawStart = (int) segment.getStart();
                        int rawEnd = Math.min(samples.length, rawStart + segment.getSamples().length);
                        int start = Math.max(0, rawStart - paddingMs * RATE / 1000);
                        int end = Math.min(samples.length, rawEnd + paddingMs * RATE / 1000);
                        segments.put(decode(recognizer, Arrays.copyOfRange(samples, start, end), start, samples.length)
                            .put("vad_start_ms", rawStart * 1000L / RATE)
                            .put("vad_end_ms", rawEnd * 1000L / RATE));
                    }
                    report.put("vad_decode_ms", SystemClock.elapsedRealtime() - started);
                    report.put("vad_segments", segments);
                } finally {
                    vad.release();
                }
                report.put("pss_after_decode_kib", Debug.getPss());
                report.put("proc_status_after_decode", new String(Files.readAllBytes(new File("/proc/self/status").toPath()), StandardCharsets.UTF_8));
            } finally {
                recognizer.release();
            }
            report.put("success", true);
        } catch (Throwable error) {
            Log.e("AsrProbe", "Probe failed", error);
            try {
                report.put("success", false);
                report.put("error", Log.getStackTraceString(error));
            } catch (Exception ignored) {
                Log.e("AsrProbe", "Cannot encode error", ignored);
            }
        }
        try {
            String json = report.toString(2);
            Files.write(new File(getFilesDir(), "result.json").toPath(), json.getBytes(StandardCharsets.UTF_8));
            runOnUiThread(() -> status.setText(json));
            Log.i("AsrProbe", "RESULT_READY success=" + report.optBoolean("success"));
        } catch (Exception error) {
            Log.e("AsrProbe", "Cannot save result", error);
        }
    }

    private static JSONObject decode(OfflineRecognizer recognizer, float[] samples, long offset, int total) throws Exception {
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(samples, RATE);
            recognizer.decode(stream);
            OfflineRecognizerResult result = recognizer.getResult(stream);
            JSONArray times = new JSONArray();
            for (float time : result.getTimestamps()) {
                times.put(time * 1000.0 + offset * 1000.0 / RATE);
            }
            return new JSONObject()
                .put("text", result.getText())
                .put("start_ms", offset * 1000L / RATE)
                .put("end_ms", Math.min(total, offset + samples.length) * 1000L / RATE)
                .put("tokens", new JSONArray(Arrays.asList(result.getTokens())))
                .put("token_start_ms", times)
                .put("durations_seconds", new JSONArray(result.getDurations()))
                .put("timing", "TOKEN");
        } finally {
            stream.release();
        }
    }

    private static void drain(Vad vad, List<SpeechSegment> speech) {
        while (!vad.empty()) {
            speech.add(vad.front());
            vad.pop();
        }
    }

    private static String required(File directory, String name) throws Exception {
        File file = new File(directory, name);
        if (!file.isFile() || file.length() == 0) {
            throw new java.io.IOException("Missing input: " + file);
        }
        return file.getAbsolutePath();
    }

    private static float[] readWave(File file) throws Exception {
        try (RandomAccessFile wave = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[12];
            wave.readFully(header);
            if (!new String(header, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                || !new String(header, 8, 4, StandardCharsets.US_ASCII).equals("WAVE")) {
                throw new java.io.IOException("Expected RIFF WAVE");
            }
            boolean formatChecked = false;
            while (wave.getFilePointer() + 8 <= wave.length()) {
                String chunk = new String(new byte[]{wave.readByte(), wave.readByte(), wave.readByte(), wave.readByte()}, StandardCharsets.US_ASCII);
                long size = Integer.toUnsignedLong(Integer.reverseBytes(wave.readInt()));
                long next = wave.getFilePointer() + size + (size & 1);
                if (size > wave.length() - wave.getFilePointer()) {
                    throw new java.io.IOException("Truncated WAV");
                }
                if (chunk.equals("fmt ")) {
                    if (size < 16) throw new java.io.IOException("Invalid fmt chunk");
                    int encoding = Short.toUnsignedInt(Short.reverseBytes(wave.readShort()));
                    int channels = Short.toUnsignedInt(Short.reverseBytes(wave.readShort()));
                    int rate = Integer.reverseBytes(wave.readInt());
                    wave.skipBytes(6);
                    int bits = Short.toUnsignedInt(Short.reverseBytes(wave.readShort()));
                    if (encoding != 1 || channels != 1 || rate != RATE || bits != 16) {
                        throw new java.io.IOException("Expected PCM16 mono 16000 Hz");
                    }
                    formatChecked = true;
                } else if (chunk.equals("data")) {
                    if (!formatChecked || size % 2 != 0 || size > 16000L * 2 * 120) {
                        throw new java.io.IOException("Probe accepts up to 120 seconds of PCM16 WAV");
                    }
                    byte[] bytes = new byte[(int) size];
                    wave.readFully(bytes);
                    ByteBuffer pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                    float[] samples = new float[bytes.length / 2];
                    for (int i = 0; i < samples.length; i++) samples[i] = pcm.getShort() / 32768f;
                    return samples;
                }
                wave.seek(next);
            }
            throw new java.io.IOException("No data chunk");
        }
    }
}
