package dev.wordbyword.app.ui.view;

public final class RecordingLabels {
  private RecordingLabels() {}

  public static String summary(int utterances, int speakers) {
    return count(utterances, "реплика", "реплики", "реплик") + " · "
        + count(speakers, "участник", "участника", "участников");
  }

  private static String count(int number, String one, String few, String many) {
    int lastTwo = number % 100;
    int last = number % 10;
    String noun = lastTwo >= 11 && lastTwo <= 14 ? many
        : last == 1 ? one : last >= 2 && last <= 4 ? few : many;
    return number + " " + noun;
  }
}
