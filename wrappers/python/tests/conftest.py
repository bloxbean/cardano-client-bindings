import pytest
from ccl._ffi import CclLib


@pytest.fixture(scope="session")
def ccl():
    """Create a shared CclLib instance for all tests."""
    lib = CclLib()
    yield lib
    lib.close()
