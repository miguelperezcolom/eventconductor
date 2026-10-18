import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))
sys.path.insert(0, os.path.dirname(__file__))

from fake_engine import FakeEngine  # noqa: E402


@pytest.fixture
def engine():
    fake = FakeEngine().start()
    yield fake
    fake.stop()
