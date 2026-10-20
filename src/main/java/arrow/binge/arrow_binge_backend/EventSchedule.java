package arrow.binge.arrow_binge_backend;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Fixed UTC schedule shared with the offline Android calendar. */
public final class EventSchedule {
    private EventSchedule() {}
    private static final int[] DAILY = {5,6,7,8,9,10,11,12,13,14,17,18,19,20,23,24,26,27,28,30,32,34};
    public static LocalDate today(Instant now) { return now.atZone(ZoneOffset.UTC).toLocalDate(); }
    public static LocalDate week(LocalDate day) { return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); }
    public static int dailyLevel(LocalDate day) { return DAILY[Math.floorMod(day.toEpochDay(), DAILY.length)]; }
    public static List<Integer> tournamentLevels(LocalDate week) {
        var levels = new ArrayList<Integer>();
        // Everyone receives the same rotation; each board can score once per week.
        for (int i=0;i<50;i++) levels.add(1 + Math.floorMod((int)(week.toEpochDay()%50) + i*17,50));
        return levels;
    }
    public static Instant weekEnd(LocalDate week) { return week.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant(); }
}
