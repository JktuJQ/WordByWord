package dev.wordbyword.evaluation;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class NativeSegmentation implements AutoCloseable {
  public record Scores(int frames, int classes, float[] values) {}
  private long handle;
  private final Map<String, Integer> metadata = new LinkedHashMap<>();

  public NativeSegmentation(Path bridge, Path model) {
    System.load(bridge.toAbsolutePath().toString());
    handle = openModel(model.toAbsolutePath().toString());
    try {
      for (String item : readMetadata(handle)) {
        String[] pair = item.split("=", 2); metadata.put(pair[0], Integer.parseInt(pair[1]));
      }
    } catch (RuntimeException error) { close(); throw error; }
  }

  public Map<String, Integer> metadata() { return Map.copyOf(metadata); }

  public Scores predict(float[] waveform) {
    if (handle == 0) throw new IllegalStateException("Closed segmentation session");
    if (waveform.length != metadata.get("window_size")) throw new IllegalArgumentException("Unexpected window size");
    float[] values = inferModel(handle, waveform); long[] shape = lastShape(handle);
    if (shape.length != 3 || shape[0] != 1 || shape[2] != metadata.get("num_classes")
        || shape[1] * shape[2] != values.length) throw new IllegalStateException("Unexpected output shape");
    return new Scores((int) shape[1], (int) shape[2], values);
  }

  @Override public void close() { if (handle != 0) { closeModel(handle); handle = 0; } }
  private static native long openModel(String model);
  private static native String[] readMetadata(long handle);
  private static native float[] inferModel(long handle, float[] waveform);
  private static native long[] lastShape(long handle);
  private static native void closeModel(long handle);
}
