import 'package:flutter_test/flutter_test.dart';
import 'package:personal_news_feed_reader/models.dart';

void main() {
  test('FeedItem.fromJson valt terug op title als titleNl leeg is', () {
    final item = FeedItem.fromJson({'id': '1', 'title': 'Hello', 'summary': 'x'});
    expect(item.displayTitle, 'Hello');
    expect(item.isPodcast, false);
  });

  test('FeedItem.fromJson gebruikt titleNl als displayTitle', () {
    final item = FeedItem.fromJson(
        {'id': '1', 'title': 'Hello', 'titleNl': 'Hallo', 'summary': 'x'});
    expect(item.displayTitle, 'Hallo');
  });
}
