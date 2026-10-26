#!/usr/bin/env python3
"""Verify recorded physical-device evidence; never installs, grants access, or drives input."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = Path.home() / 'Library/Android/sdk/build-tools/36.0.0'


def verify(report, apk):
    checks = {}

    def check(label, condition):
        checks[label] = bool(condition)
        if not condition:
            raise ValueError('Evidence check failed: ' + label)

    check('ordinary_app_uid', report['uid'] >= 10000)
    grants = report.get('effective_privileged_permissions', {})
    check('runtime_privileged_grants_checked', set(grants) == {
        'INJECT_EVENTS', 'ADD_TRUSTED_DISPLAY', 'ACTIVITY_EMBEDDING',
        'CAPTURE_VIDEO_OUTPUT', 'CAPTURE_SECURE_VIDEO_OUTPUT', 'WRITE_SECURE_SETTINGS'})
    check('no_privileged_grants', not any(grants.values()))
    permissions = subprocess.check_output([str(TOOLS / 'aapt2'), 'dump', 'permissions', str(apk)], text=True)
    requested = set(re.findall(r"uses-permission: name='([^']+)'", permissions))
    check('only_capture_and_notification_permissions', requested == {
        'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION',
        'android.permission.POST_NOTIFICATIONS'})
    with zipfile.ZipFile(apk) as source:
        dex = source.read('classes.dex')
    check('no_helper_or_shell_client_in_dex', all(token not in dex for token in [
        b'dev.beampro.portprobe', b'LaunchServer', b'app_process', b'WRITE_SECURE_SETTINGS_GRANTED',
        b'java/lang/Runtime;', b'java/lang/ProcessBuilder;']))
    probe_apk = ROOT / 'artifacts/probe/beam-port-probe.apk'
    def signer(path):
        text = subprocess.check_output([str(TOOLS / 'apksigner'), 'verify', '--print-certs', str(path)], text=True)
        return re.search(r'certificate SHA-256 digest: (\w+)', text).group(1)
    check('different_certificate_from_helper_companion', signer(apk) != signer(probe_apk))
    cases = report['virtual_display_cases']
    control = next(item for item in cases if item['case'] == 'own_presentation_positive_control')
    check('own_content_capture_positive_control', control['passed'] and control['capture']['nonblack_frames'] > 0)
    attempts = [item for item in cases if item['case'] == 'two_real_apps']
    check('all_five_virtual_display_configurations_tested', sorted(item['flags'] for item in attempts) == [0, 8, 9, 10, 11])
    check('ten_real_app_launches_rejected', all(len(item['apps']) == 2 and all(
        app.get('activity_start_allowed') is False and 'SecurityException' in app.get('launch_error', '')
        and app['capture']['frames'] == 0 for app in item['apps']) for item in attempts))
    check('independent_virtual_windows_not_reported_as_passed', report['independent_virtual_apps_passed'] is False)
    check('actual_accessibility_click_received', report['pointer_self_check']['gesture_completed']
          and report['pointer_self_check']['actual_click_received'])
    split = report['split_capture']
    names = {'com.finalwire.aida64', 'com.android.chrome'}
    check('two_real_apps_selected', set(split['packages']) == names and len(split['packages']) == 2)
    geometry = {item['package']: item for item in split['window_geometry'] if item['package'] in names}
    check('both_app_windows_observed', set(geometry) == names and split['two_disjoint_app_windows_visible'])
    rectangles = []
    for name in sorted(names):
        rectangles.append(tuple(map(int, geometry[name]['node_bounds'].split())))
    a, b = rectangles
    check('app_regions_nonempty_and_do_not_overlap', a[0] < a[2] and a[1] < a[3]
          and b[0] < b[2] and b[1] < b[3]
          and (a[2] <= b[0] or b[2] <= a[0] or a[3] <= b[1] or b[3] <= a[1]))
    check('both_app_captures_changed', len(split['crops']) == 2
          and {item['package'] for item in split['crops']} == names
          and all(item['captured_samples'] > 1 and item['changed_frames'] > 0 for item in split['crops']))
    inputs = {item['package']: item for item in split['input']}
    check('both_apps_responded_to_accessibility_input', set(inputs) == names
          and all(item['gesture_completed'] and item['target_visible'] and item['observed_response'] for item in inputs.values())
          and inputs['com.android.chrome']['input_focused'] and inputs['com.finalwire.aida64']['content_changed'])
    check('split_result_kept_separate_from_independent_windows', split['independent_virtual_displays'] is False
          and split['passed'] and report['split_capture_two_apps_passed'])
    check('capture_stopped_after_test', report['capture_active'] is False)
    check('ar_space_not_reported_as_tested', report['ar_space_integration_tested'] is False)
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=ROOT / 'artifacts/standalone')
    args = parser.parse_args()
    directory = args.directory.resolve()
    apk = directory / 'unbound-standalone.apk'
    evidence = directory / 'oneplus-runtime-results.json'
    checks = verify(json.loads(evidence.read_text()), apk)
    report = {'passed': True, 'checks': checks, 'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
              'runtime_evidence_sha256': hashlib.sha256(evidence.read_bytes()).hexdigest(),
              'latest_apk_full_runtime_validated': False,
              'runtime_evidence_build': 'earlier successful prototype build; latest repeat stopped at owner request',
              'scope': 'OnePlus 15 Android 16; two-app split capture and Accessibility input; no AR Space integration'}
    (directory / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'{len(checks)} evidence checks passed')


if __name__ == '__main__':
    main()
