import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:personal_news_feed/main.dart';

void main() {
  testWidgets('App boots without crashing', (WidgetTester tester) async {
    await tester.pumpWidget(const ProviderScope(child: NewsFeedApp()));
    await tester.pump();
    expect(find.byType(MaterialApp), findsOneWidget);
  });

  testWidgets('App theme uses the teal seed color (0xFF00897B)',
      (WidgetTester tester) async {
    await tester.pumpWidget(const ProviderScope(child: NewsFeedApp()));
    await tester.pump();

    final materialApp = tester.widget<MaterialApp>(find.byType(MaterialApp));
    final expectedScheme =
        ColorScheme.fromSeed(seedColor: const Color(0xFF00897B));
    expect(materialApp.theme?.colorScheme.primary, expectedScheme.primary);
  });
}
