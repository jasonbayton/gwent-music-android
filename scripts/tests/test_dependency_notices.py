import importlib.util
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('notices', Path(__file__).parents[1] / 'generate-dependency-notices.py')
notices = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notices)


class DependencyNoticesTest(unittest.TestCase):
    def parse(self, body, coordinate='example:library:1'):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'metadata.pom'
            path.write_text(body)
            return notices.pom_licenses(coordinate, path)

    def test_namespaced_and_plain_poms(self):
        for ns in ('', ' xmlns="http://maven.apache.org/POM/4.0.0"'):
            result = self.parse(f'<project{ns}><licenses><license><name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license></licenses></project>')
            self.assertEqual(result[0][0], 'Apache-2.0')

    def test_unknown_or_missing_licence_fails_closed(self):
        for body in ('<project/>', '<project><licenses><license><name>Unreviewed licence</name></license></licenses></project>'):
            with self.assertRaises(ValueError):
                self.parse(body)

    def test_misleading_apache_names_require_review(self):
        for name in ('Apache-1.2', 'Apache custom 2', 'Apache-2.0 with additional restrictions'):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'New licence requires review'):
                self.parse(f'<project><licenses><license><name>{name}</name></license></licenses></project>')

    def public_suffix_archive(self, content=b'reviewed synthetic dataset'):
        archive_bytes = io.BytesIO()
        with zipfile.ZipFile(archive_bytes, 'w') as archive:
            archive.writestr('assets/PublicSuffixDatabase.list', content)
        return archive_bytes.getvalue()

    def test_reviewed_public_suffix_version_and_hash_are_accepted(self):
        content = b'reviewed synthetic dataset'
        with patch.object(notices, 'PSL_SHA256', hashlib.sha256(content).hexdigest()):
            notices.check_public_suffix(notices.PSL_COORDINATE, self.public_suffix_archive(content))

    def test_public_suffix_version_change_requires_review_even_with_same_dataset(self):
        content = b'reviewed synthetic dataset'
        coordinate = notices.PSL_COORDINATE.rsplit(':', 1)[0] + ':999.0.0'
        with patch.object(notices, 'PSL_SHA256', hashlib.sha256(content).hexdigest()):
            with self.assertRaisesRegex(ValueError, 'Review updated Public Suffix'):
                notices.check_public_suffix(coordinate, self.public_suffix_archive(content))

    def test_public_suffix_hash_change_requires_review_at_same_version(self):
        with patch.object(notices, 'PSL_SHA256', hashlib.sha256(b'original dataset').hexdigest()):
            with self.assertRaisesRegex(ValueError, 'Review updated Public Suffix'):
                notices.check_public_suffix(notices.PSL_COORDINATE, self.public_suffix_archive(b'changed dataset'))

    def test_missing_public_suffix_dataset_fails_closed(self):
        archive_bytes = io.BytesIO()
        with zipfile.ZipFile(archive_bytes, 'w') as archive:
            archive.writestr('assets/renamed.list', b'dataset')
        with self.assertRaises(KeyError):
            notices.check_public_suffix(notices.PSL_COORDINATE, archive_bytes.getvalue())

    def test_parent_exception_is_exact(self):
        pom = '<project><parent><groupId>com.google.guava</groupId><artifactId>guava-parent</artifactId><version>26.0-android</version></parent></project>'
        self.assertIn('inherited', self.parse(pom, 'com.google.guava:listenablefuture:1.0')[0][0])
        with self.assertRaises(ValueError):
            self.parse(pom.replace('26.0-android', '27.0-android'), 'com.google.guava:listenablefuture:1.0')

    def test_nested_notice_is_retained(self):
        inner = io.BytesIO()
        with zipfile.ZipFile(inner, 'w') as archive:
            archive.writestr('META-INF/NOTICE.md', 'Example notice\n')
        outer = io.BytesIO()
        with zipfile.ZipFile(outer, 'w') as archive:
            archive.writestr('classes.jar', inner.getvalue())
        self.assertEqual(notices.archive_notices(outer.getvalue()), [('classes.jar!META-INF/NOTICE.md', 'Example notice\n')])

    def test_empty_inventory_is_rejected(self):
        with self.assertRaises(ValueError):
            notices.generate({'components': [], 'artifacts': []})
