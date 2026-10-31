package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.rwth_aachen.phyphox.device.CameraClockPairing;

//The pairing maths of the camera-clock test: accelerometer tap onsets against camera-frame shake
//peaks. bias = (frame timestamp + half a frame) - acceleration onset, so a camera clock that
//runs behind the sensor clock gives a positive bias.
public class CameraClockPairingTest {

    private static final long MS = 1_000_000L;
    private static final long HALF = 16 * MS;

    private static List<Long> taps(long... ms) {
        List<Long> out = new ArrayList<>();
        for (long m : ms)
            out.add(m * MS);
        return out;
    }

    @Test
    public void aConstantClockOffsetIsRecoveredFromEveryTap() {
        long offset = 200 * MS;
        List<Long> accel = taps(1000, 2000, 3000, 4000, 5000, 6000);
        List<Long> cam = new ArrayList<>();
        for (long a : accel)
            cam.add(a + offset - HALF); //the frame that carries the shake
        List<Long> bias = CameraClockPairing.pair(accel, cam, HALF);
        assertEquals(6, bias.size());
        for (long b : bias)
            assertEquals(offset, b);
        assertEquals(offset, CameraClockPairing.median(bias));
    }

    @Test
    public void aMissedShakePeakDoesNotShiftTheLaterPairs() {
        long offset = 150 * MS;
        List<Long> accel = taps(1000, 2000, 3000, 4000, 5000, 6000, 7000);
        List<Long> cam = new ArrayList<>();
        for (int i = 0; i < accel.size(); i++)
            if (i != 2) //the third tap shook no frame
                cam.add(accel.get(i) + offset - HALF);
        List<Long> bias = CameraClockPairing.pair(accel, cam, HALF);
        assertEquals(6, bias.size());
        for (long b : bias)
            assertEquals(offset, b);
    }

    @Test
    public void tooFewPairsAreReportedAsTooFew() {
        List<Long> accel = taps(1000, 2000, 3000);
        List<Long> cam = taps(1100, 2100, 3100);
        assertTrue(CameraClockPairing.pair(accel, cam, HALF).size() < CameraClockPairing.MIN_PAIRS);
    }

    @Test
    public void shakePeaksNeedALocalMaximumAboveTheJumpThreshold() {
        List<Double> luma = Arrays.asList(0.1, 0.1, 3.0, 0.2, 0.1, 1.0, 0.1, 5.0, 0.2);
        List<Long> t = new ArrayList<>();
        for (int i = 0; i < luma.size(); i++)
            t.add(i * 500L * MS); //500 ms apart: clear of the retrigger gap
        List<Long> peaks = CameraClockPairing.shakePeaks(luma, t);
        assertEquals(Arrays.asList(1000L * MS, 3500L * MS), peaks); //1.0 stays below LUMA_JUMP
    }

    @Test
    public void framePeriodFallsBackWhenThereAreTooFewFrames() {
        assertEquals(33_000_000L, CameraClockPairing.framePeriodNs(taps(0, 33)));
        assertEquals(20 * MS, CameraClockPairing.framePeriodNs(taps(0, 20, 40, 60, 80)));
    }
}
