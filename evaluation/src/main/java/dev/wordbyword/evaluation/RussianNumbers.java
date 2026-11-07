package dev.wordbyword.evaluation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class RussianNumbers {
  private static final Map<String, Integer> VALUES = new HashMap<>();
  private static final Map<String, Long> SCALES = new HashMap<>();
  private static final Set<String> SINGULAR_SCALES =
      Set.of("тысяча", "тысячу", "миллион", "миллиард", "триллион");
  private static final Pattern GROUPED_DIGITS =
      Pattern.compile("(?<![\\p{L}\\p{N}])([1-9][0-9]{0,2}(?: [0-9]{3})+)(?![\\p{L}\\p{N}])");

  static {
    String[] small =
        "ноль один два три четыре пять шесть семь восемь девять десять одиннадцать двенадцать тринадцать четырнадцать пятнадцать шестнадцать семнадцать восемнадцать девятнадцать"
            .split(" ");
    for (int i = 0; i < small.length; i++) VALUES.put(small[i], i);
    String[] tens =
        "двадцать тридцать сорок пятьдесят шестьдесят семьдесят восемьдесят девяносто".split(" ");
    for (int i = 0; i < tens.length; i++) VALUES.put(tens[i], (i + 2) * 10);
    String[] hundreds =
        "сто двести триста четыреста пятьсот шестьсот семьсот восемьсот девятьсот".split(" ");
    for (int i = 0; i < hundreds.length; i++) VALUES.put(hundreds[i], (i + 1) * 100);
    VALUES.put("нуль", 0);
    VALUES.put("одна", 1);
    VALUES.put("одно", 1);
    VALUES.put("две", 2);
    String[][] scales = {
      {"тысяча", "тысячу", "тысячи", "тысяч"},
      {"миллион", "миллиона", "миллионов"},
      {"миллиард", "миллиарда", "миллиардов"},
      {"триллион", "триллиона", "триллионов"}
    };
    long scale = 1000;
    for (String[] forms : scales) {
      for (String form : forms) SCALES.put(form, scale);
      scale *= 1000;
    }
  }

  private RussianNumbers() {}

  static String canonicalize(String text) {
    text = GROUPED_DIGITS.matcher(text).replaceAll(match -> match.group().replace(" ", ""));
    List<String> tokens = text.isBlank() ? List.of() : List.of(text.split(" "));
    var result = new ArrayList<String>();
    for (int i = 0; i < tokens.size(); ) {
      int start = i;
      BigInteger total = BigInteger.ZERO;
      BigInteger group = BigInteger.ZERO;
      long lastScale = Long.MAX_VALUE;
      int lastKind = 0;
      while (i < tokens.size()) {
        String token = tokens.get(i);
        Integer value = VALUES.get(token);
        Long scale = SCALES.get(token);
        if (value != null) {
          int kind = value >= 100 ? 1 : value >= 20 ? 2 : 3;
          if (kind <= lastKind || (lastKind == 2 && value >= 10)) break;
          group = group.add(BigInteger.valueOf(value));
          lastKind = kind;
        } else if (scale != null
            && scale < lastScale
            && (lastKind != 0 || SINGULAR_SCALES.contains(token))) {
          total =
              total.add(
                  (lastKind == 0 ? BigInteger.ONE : group).multiply(BigInteger.valueOf(scale)));
          group = BigInteger.ZERO;
          lastKind = 0;
          lastScale = scale;
        } else if (token.matches("[0-9]+") && lastKind == 0) {
          if (token.length() > 1 && token.startsWith("0")) break;
          group = new BigInteger(token);
          lastKind = 3;
        } else break;
        i++;
      }
      if (i == start) {
        result.add(tokens.get(i++));
      } else {
        result.add(total.add(group).toString());
      }
    }
    return String.join(" ", result);
  }
}
