package dev.wordbyword.app.core.processing;

/**
 * Recoverable processing failure. Adapters retain the original cause; UI chooses the user message.
 */
public class ProcessingException extends Exception {
  public ProcessingException(String message) {
    super(message);
  }

  public ProcessingException(String message, Throwable cause) {
    super(message, cause);
  }
}
