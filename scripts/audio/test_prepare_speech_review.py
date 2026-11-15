"""Offline signal-invariant checks for the reversible speech review candidate."""
import importlib.util
from pathlib import Path
import unittest

import numpy as np

SPEC = importlib.util.spec_from_file_location('prepare_speech_review', Path(__file__).with_name('prepare_speech_review.py'))
REVIEW = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REVIEW)


class SpeechReviewTests(unittest.TestCase):
    def test_gain_cap_and_peak_headroom_preserve_frame_count(self):
        rate = 24_000
        time = np.arange(rate) / rate
        loud = .98 * np.sin(2 * np.pi * 440 * time)
        pcm, parameters = REVIEW.candidate(loud, rate)
        self.assertEqual(len(pcm), len(loud))
        self.assertGreaterEqual(parameters['applied_gain_db'], -6)
        self.assertLessEqual(parameters['applied_gain_db'], 6)
        self.assertLessEqual(np.max(np.abs(pcm.astype(np.float64) / 32768)), 10 ** (-3 / 20))
        self.assertEqual(0, pcm[0])
        self.assertEqual(0, pcm[-1])

    def test_quiet_input_is_not_amplified_beyond_cap(self):
        rate = 24_000
        time = np.arange(rate) / rate
        quiet = .001 * np.sin(2 * np.pi * 220 * time)
        pcm, parameters = REVIEW.candidate(quiet, rate)
        self.assertEqual(6, parameters['applied_gain_db'])
        self.assertTrue(parameters['gain_cap_active'])
        self.assertEqual(48, parameters['edge_fade_samples_each'])
        # The interior uses one constant gain, apart from final PCM16 quantization.
        expected = quiet * 10 ** (6 / 20)
        self.assertLessEqual(np.max(np.abs(pcm[48:-48] / 32768 - expected[48:-48])), .5 / 32768 + 1e-15)

    def test_high_crest_input_uses_peak_bound_without_limiting(self):
        samples = np.zeros(24_000)
        samples[12_000] = .8
        pcm, parameters = REVIEW.candidate(samples, 24_000)
        self.assertTrue(parameters['peak_cap_active'])
        self.assertLess(parameters['applied_gain_db'], 0)
        self.assertEqual(1, np.count_nonzero(pcm))

    def test_invalid_or_silent_input_is_rejected(self):
        for invalid in (np.zeros(24_000), np.array([]), np.array([np.nan]), np.zeros((100, 2))):
            with self.assertRaises(ValueError):
                REVIEW.candidate(invalid, 24_000)


if __name__ == '__main__':
    unittest.main()
