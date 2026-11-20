import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Darwin native sources do not reference the private broadcast selector',
      () {
    final sources = Directory('common/darwin/Classes')
        .listSync(recursive: true)
        .whereType<File>()
        .where((file) => file.path.endsWith('.m') || file.path.endsWith('.h'));

    expect(sources, isNotEmpty);
    for (final source in sources) {
      expect(
        source.readAsStringSync(),
        isNot(contains('buttonPressed:')),
        reason: '${source.path} must not reference the private ReplayKit API',
      );
    }
  });
}
