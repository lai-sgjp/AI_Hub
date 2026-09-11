import unittest

from upgrade_state import verify_preserved


class UpgradeStateTest(unittest.TestCase):
    def setUp(self):
        self.records = {
            'room': ('room', '{"name":"saved chat"}'),
            'profile': ('profile', '{"model":"saved model"}'),
        }
        self.before = (self.records, b'encrypted credentials')

    def test_identical_state_passes(self):
        self.assertEqual(verify_preserved(self.before, self.before), 0)

    def test_new_world_and_bundle_are_allowed(self):
        after = (dict(self.records, world=('world', '{}'), bundle=('bundle', 'ready')), self.before[1])
        self.assertEqual(verify_preserved(self.before, after), 2)

    def test_deleted_record_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'records'):
            verify_preserved(self.before, ({'room': self.records['room']}, self.before[1]))

    def test_changed_record_is_rejected_without_printing_private_data(self):
        for replacement in [('profile', 'PRIVATE'), ('world', self.records['profile'][1])]:
            with self.subTest(replacement=replacement):
                with self.assertRaises(ValueError) as error:
                    verify_preserved(self.before, (dict(self.records, profile=replacement), self.before[1]))
                self.assertNotIn('PRIVATE', str(error.exception))

    def test_changed_or_removed_credentials_are_rejected(self):
        for secret in [b'other', None]:
            with self.subTest(secret=secret), self.assertRaisesRegex(ValueError, 'credentials'):
                verify_preserved(self.before, (self.records, secret))

    def test_no_configured_credentials_is_supported(self):
        self.assertEqual(verify_preserved((self.records, None), (self.records, None)), 0)


if __name__ == '__main__':
    unittest.main()
