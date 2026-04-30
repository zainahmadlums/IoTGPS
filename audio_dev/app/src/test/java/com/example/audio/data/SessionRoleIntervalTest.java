package com.example.audio.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SessionRoleIntervalTest {

    @Test
    public void storesRoleWithStartAndEndOffsets() {
        SessionRoleInterval interval = new SessionRoleInterval(
                SpeakerRole.INSTRUCTOR,
                1200L,
                3400L,
                2200L
        );

        assertEquals(SpeakerRole.INSTRUCTOR, interval.getRole());
        assertEquals(1200L, interval.getStartOffsetMillis());
        assertEquals(3400L, interval.getEndOffsetMillis());
        assertEquals(2200L, interval.getDurationMillis());
    }
}
