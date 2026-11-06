package dev.wordbyword.app.core.processing;

public class ProcessingException extends Exception {
  private static final long serialVersionUID = 1L;

  public ProcessingException(String message) {
    super(message);
  }

  public ProcessingException(String message, Throwable cause) {
    super(message, cause);
  }
}
