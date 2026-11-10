import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import urllib.request
import zipfile

import publish_release as pub
from release_metadata import build_metadata


class PublishingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.metadata = build_metadata('v0.0.3.1', 'mesplicedterminal', '1.21.1')
        self.jar = Path(self.temp.name) / self.metadata['expected_jar']
        with zipfile.ZipFile(self.jar, 'w') as jar:
            jar.writestr('META-INF/neoforge.mods.toml', '[[mods]]\nmodId="mesplicedterminal"\nversion="0.0.3.1"\n')
            jar.writestr('com/lhy/mest/Test.class', b'\xca\xfe\xba\xbe')
        self.release = {'id': 1, 'draft': False, 'body': '### English\n1. Fixed: Test.\n\n### 中文\n1. 修复：测试。',
                        'upload_url': 'https://uploads.github.com/repos/owner/repo/releases/1/assets{?name,label}',
                        'html_url': 'https://github.com/owner/repo/releases/tag/v0.0.3.1'}
        self.asset = {'id': 2, 'name': self.jar.name, 'size': self.jar.stat().st_size, 'state': 'uploaded'}

    def test_missing_jar_cannot_create_github_release(self):
        api = Mock()
        with self.assertRaises((ValueError, OSError)):
            pub.publish_github(api, '/repos/owner/repo', self.metadata, self.jar.with_name('missing.jar'), 'notes', 'sha')
        api.request.assert_not_called()

    def test_empty_notes_cannot_create_github_release(self):
        api = Mock()
        with self.assertRaises(pub.PublishError):
            pub.publish_github(api, '/repos/owner/repo', self.metadata, self.jar, ' ', 'sha')
        api.request.assert_not_called()

    def test_moved_tag_cannot_publish(self):
        api = Mock()
        with patch.object(pub, 'current_commit', return_value='different'):
            with self.assertRaises(pub.PublishError):
                pub.publish_github(api, '/repos/owner/repo', self.metadata, self.jar, 'notes', 'sha')
        api.request.assert_not_called()

    def test_new_release_is_draft_until_verified_upload(self):
        api = Mock()
        api.request.side_effect = [None, dict(self.release, draft=True), self.release]
        events = []
        with patch.object(pub, 'current_commit', return_value='sha'), \
                patch.object(pub, 'replace_asset', side_effect=lambda *a: events.append('jar')):
            pub.publish_github(api, '/repos/owner/repo', self.metadata, self.jar, 'notes', 'sha')
        calls = api.request.call_args_list
        self.assertTrue(calls[1].kwargs['data']['draft'])
        self.assertFalse(calls[2].kwargs['data']['draft'])
        self.assertEqual(['jar'], events)

    def test_upload_failure_never_publishes_draft(self):
        api = Mock()
        api.request.side_effect = [None, dict(self.release, draft=True)]
        with patch.object(pub, 'current_commit', return_value='sha'), \
                patch.object(pub, 'replace_asset', side_effect=pub.PublishError('upload failed')):
            with self.assertRaises(pub.PublishError):
                pub.publish_github(api, '/repos/owner/repo', self.metadata, self.jar, 'notes', 'sha')
        self.assertEqual(2, api.request.call_count)

    def test_missing_empty_draft_or_duplicate_assets_block_cf(self):
        for release, assets in [(self.release, []), (self.release, [dict(self.asset, size=0)]),
                                (dict(self.release, draft=True), [self.asset]),
                                (dict(self.release, body=''), [self.asset]),
                                (self.release, [self.asset, self.asset])]:
            with self.subTest(release=release, assets=assets):
                with self.assertRaises(pub.PublishError):
                    pub.select_jar(release, assets, self.jar.name)
        self.assertEqual(self.asset, pub.select_jar(self.release, [self.asset], self.jar.name))

    def test_cf_upload_contains_real_jar_and_release_text(self):
        gh, cf = Mock(), Mock()
        gh.request.side_effect = [self.release, self.jar.read_bytes()]
        cf.request.side_effect = [[{'id': i, 'name': name} for i, name in
                                   enumerate(('1.21.1', 'NeoForge', 'Client', 'Server'), 1)], {'id': 99}]
        with patch.object(pub, 'assets', return_value=[self.asset]), patch.object(pub, 'replace_asset') as receipt:
            pub.sync_curseforge(gh, cf, '/repos/owner/repo', 'v0.0.3.1', '1715792', '1.21.1')
        call = cf.request.call_args
        self.assertEqual('/projects/1715792/upload-file', call.args[1])
        self.assertIn(b'name="file"', call.kwargs['data'])
        self.assertIn(self.jar.read_bytes(), call.kwargs['data'])
        self.assertIn('修复：测试。'.encode(), call.kwargs['data'])
        self.assertEqual(pub.LEDGER_NAME, receipt.call_args.args[3])

    def test_cf_missing_jar_makes_no_upload_request(self):
        gh, cf = Mock(), Mock()
        gh.request.return_value = self.release
        with patch.object(pub, 'assets', return_value=[]):
            with self.assertRaises(pub.PublishError):
                pub.sync_curseforge(gh, cf, '/repos/owner/repo', 'v0.0.3.1', '1715792', '1.21.1')
        cf.request.assert_not_called()

    def receipt(self, notes_hash):
        return {'schema': 1, 'files': [{'sha256': hashlib.sha256(self.jar.read_bytes()).hexdigest(),
                'project_id': '1715792', 'file_id': 99, 'body_sha256': notes_hash}]}

    def test_same_file_and_notes_rerun_does_not_reupload(self):
        gh, cf = Mock(), Mock()
        digest = hashlib.sha256(self.release['body'].encode()).hexdigest()
        gh.request.side_effect = [self.release, self.jar.read_bytes(), json.dumps(self.receipt(digest)).encode()]
        with patch.object(pub, 'assets', return_value=[self.asset, {'id': 3, 'name': pub.LEDGER_NAME}]):
            pub.sync_curseforge(gh, cf, '/repos/owner/repo', 'v0.0.3.1', '1715792', '1.21.1')
        cf.request.assert_not_called()

    def test_changed_release_text_updates_existing_file_after_jar_check(self):
        gh, cf = Mock(), Mock()
        gh.request.side_effect = [self.release, self.jar.read_bytes(), json.dumps(self.receipt('old')).encode()]
        cf.request.side_effect = [[{'id': i, 'name': name} for i, name in
                                   enumerate(('1.21.1', 'NeoForge', 'Client', 'Server'), 1)], {'id': 99}]
        with patch.object(pub, 'assets', return_value=[self.asset, {'id': 3, 'name': pub.LEDGER_NAME}]), \
                patch.object(pub, 'replace_asset'):
            pub.sync_curseforge(gh, cf, '/repos/owner/repo', 'v0.0.3.1', '1715792', '1.21.1')
        self.assertEqual('/projects/1715792/update-file', cf.request.call_args.args[1])
        self.assertIn(b'"fileID": 99', cf.request.call_args.kwargs['data'])
        self.assertIn('修复：测试。'.encode(), cf.request.call_args.kwargs['data'])

    def test_replacement_upload_failure_preserves_old_asset(self):
        api = Mock()
        api.request.side_effect = [b'old', pub.PublishError('upload failed')]
        with patch.object(pub, 'assets', return_value=[self.asset]):
            with self.assertRaises(pub.PublishError):
                pub.replace_asset(api, '/repos/owner/repo', self.release, self.jar.name, b'new', 'application/java-archive')
        self.assertFalse(any(call.args[0] == 'DELETE' for call in api.request.call_args_list))

    def test_redirect_does_not_forward_tokens_to_asset_storage(self):
        request = urllib.request.Request('https://api.github.com/a', headers={'Authorization': 'Bearer secret'})
        redirect = pub.SafeRedirect().redirect_request(request, None, 302, 'Found', {}, 'https://release-assets.githubusercontent.com/a')
        self.assertIsNone(redirect.get_header('Authorization'))
        with self.assertRaises(pub.PublishError):
            pub.SafeRedirect().redirect_request(request, None, 302, 'Found', {}, 'http://example.com/a')

    def test_unknown_cf_version_fails_before_upload(self):
        cf = Mock()
        cf.request.return_value = []
        with self.assertRaises(pub.PublishError):
            pub.curseforge_version_ids(cf, '1.21.1')
        cf.request.assert_called_once_with('GET', '/game/versions')

    def test_cf_requires_positive_project_id(self):
        gh, cf = Mock(), Mock()
        with self.assertRaises(pub.PublishError):
            pub.sync_curseforge(gh, cf, '/repos/owner/repo', 'v0.0.3.1', '', '1.21.1')
        gh.request.assert_not_called()
        cf.request.assert_not_called()


if __name__ == '__main__':
    unittest.main()
