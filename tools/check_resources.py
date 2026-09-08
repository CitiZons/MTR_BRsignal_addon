"""Run resource checks without regenerating authored model files or previews."""
import sys
import unittest

from check_position_light_signals import validate
from check_speed_signs import run


def main():
    suite = unittest.TestSuite()
    for name in ("check_triple_indicators", "check_six_route_indicators", "check_indicator_textures", "check_signal_mounts"):
        tests = unittest.defaultTestLoader.loadTestsFromName(name)
        def add(test):
            if isinstance(test, unittest.TestSuite):
                for child in test:
                    add(child)
            elif not test.id().endswith("test_regenerator_preserves_authored_resources_and_is_idempotent"):
                suite.addTest(test)
        add(tests)
    if not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful():
        return 1
    validate()
    run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
