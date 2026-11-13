import unittest
from restore_dump_verifier import compare, VerificationError

SOURCE = (b'CREATE TABLE `credit_account` (\n'
          b'  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,\n'
          b'  `balance` bigint NOT NULL DEFAULT 0,\n'
          b'  PRIMARY KEY (`id`)\n'
          b') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;\n'
          b"INSERT INTO `credit_account` VALUES ('owner',10);\n")
RESTORED = SOURCE.replace(b'varchar(36) COLLATE', b'varchar(36) CHARACTER SET utf8mb4 COLLATE')


class RestoreVerificationTest(unittest.TestCase):
    def test_only_redundant_charset_is_accepted(self):
        result = compare(SOURCE, RESTORED)
        self.assertEqual(result['tables']['credit_account']['rows'], 1)
        self.assertNotEqual(result['sourceSha256'], result['restoredSha256'])

    def test_real_changes_are_rejected(self):
        mutations = [
            RESTORED.replace(b",10);", b",11);"),
            RESTORED.replace(b"'owner'", b"'other'"),
            RESTORED.replace(b'varchar(36)', b'varchar(35)'),
            RESTORED.replace(b'utf8mb4_unicode_ci', b'utf8mb4_bin'),
            RESTORED.replace(b'CHARACTER SET utf8mb4', b'CHARACTER SET latin1'),
            RESTORED.replace(b'PRIMARY KEY (`id`)', b'KEY (`id`)'),
            RESTORED.replace(b'DEFAULT 0', b'DEFAULT 1'),
            RESTORED[:RESTORED.index(b'INSERT INTO')],
            RESTORED + b"INSERT INTO `credit_account` VALUES ('extra',10);\n",
            RESTORED + b'SET FOREIGN_KEY_CHECKS=0;\n',
        ]
        for changed in mutations:
            with self.subTest(changed=changed), self.assertRaises(VerificationError):
                compare(SOURCE, changed)

    def test_text_values_are_never_normalized(self):
        source = SOURCE.replace(b"'owner'", b"' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci '")
        changed = source.replace(b"' CHARACTER SET utf8mb4", b"'")
        with self.assertRaises(VerificationError):
            compare(source, changed)


if __name__ == '__main__':
    unittest.main()
