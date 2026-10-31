package de.rwth_aachen.phyphox.device;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

//The pairing maths of the camera-clock test, free of any UI so it can be tested.
//
//A sharp tap spikes the accelerometer and shakes the camera frame at the same real instant.
//Each pair contributes bias = (frameTimestamp + halfFrame) - accelerationOnset: the frame whose
//exposure straddles the tap carries the shake, and mid-exposure is its best single-point estimate.
public final class CameraClockPairing {
    public static final int MIN_PAIRS = 5;                  //more taps -> median converges
    public static final double LUMA_JUMP = 1.8;             //shake-induced luma diff
    public static final long TAP_GAP_NS = 400_000_000L;     //retrigger gap for tap clustering
    public static final long WINDOW_NS = 900_000_000L;      //pairing window around each accel tap
    public static final long REFINE_NS = 300_000_000L;      //second pass: distance from the median anchor

    private CameraClockPairing() {}

    //Frame timestamps at which the luma difference to the previous frame is a local peak above
    //LUMA_JUMP. lumaDiff[i] belongs to frameTimes[i].
    public static List<Long> shakePeaks(List<Double> lumaDiff, List<Long> frameTimes) {
        List<Long> peaks = new ArrayList<>();
        for (int i = 1; i + 1 < lumaDiff.size(); i++) {
            double d = lumaDiff.get(i);
            if (d > LUMA_JUMP && d >= lumaDiff.get(i - 1) && d >= lumaDiff.get(i + 1)) {
                long ts = frameTimes.get(i);
                if (peaks.isEmpty() || ts - peaks.get(peaks.size() - 1) > TAP_GAP_NS)
                    peaks.add(ts);
            }
        }
        return peaks;
    }

    //Per-tap biases (sorted), or whatever fewer than MIN_PAIRS pairs could be found.
    public static List<Long> pair(List<Long> accelOnsets, List<Long> camPeaks, long halfFrameNs) {
        //Pass 1: rough pairing gets a median anchor; pass 2 re-pairs within REFINE_NS of it so
        //a missed luma peak or an off-by-one bind cannot drag every later pair along.
        List<Long> bias = pairWithin(accelOnsets, camPeaks, WINDOW_NS, halfFrameNs);
        if (bias.size() >= MIN_PAIRS) {
            Collections.sort(bias);
            long anchor = bias.get(bias.size() / 2);
            List<Long> refined = new ArrayList<>();
            for (long a : accelOnsets) {
                long best = -1, bestDist = Long.MAX_VALUE;
                for (long c : camPeaks) {
                    long dist = Math.abs(c + halfFrameNs - a - anchor);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = c;
                    }
                }
                if (best >= 0 && bestDist < REFINE_NS)
                    refined.add(best + halfFrameNs - a);
            }
            if (refined.size() >= MIN_PAIRS)
                bias = refined;
        }
        Collections.sort(bias);
        return bias;
    }

    static List<Long> pairWithin(List<Long> accelOnsets, List<Long> camPeaks,
                                 long windowNs, long halfFrameNs) {
        List<Long> camLeft = new ArrayList<>(camPeaks);
        List<Long> out = new ArrayList<>();
        for (long a : accelOnsets) {
            Long best = null;
            long bestDist = Long.MAX_VALUE;
            for (long c : camLeft) {
                long dist = Math.abs(c + halfFrameNs - a);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = c;
                }
            }
            if (best != null && bestDist < windowNs) {
                camLeft.remove(best);
                out.add(best + halfFrameNs - a);
            }
        }
        return out;
    }

    //Median of a non-empty list; the upper median for an even count, as before.
    public static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    //Mean frame period from timestamps; 33 ms when there are too few frames to say.
    public static long framePeriodNs(List<Long> frameTimes) {
        if (frameTimes.size() < 4)
            return 33_000_000L;
        return (frameTimes.get(frameTimes.size() - 1) - frameTimes.get(0)) / (frameTimes.size() - 1);
    }
}
