import 'package:flutter_test/flutter_test.dart';
import 'package:personal_news_feed/models/models.dart';

/// Eerste tests op het JSON-contract van de hoofd-app: [FeedItem] en [RssItem].
///
/// Deze twee modellen stromen door de feed- en RSS-schermen en zijn de enige
/// twee met de `_Sentinel`-constructie in `copyWith`. `models.dart` heeft geen
/// imports, dus kale `test(...)`-blokken volstaan: geen mocks, geen binding.
void main() {
  group('FeedItem', () {
    // De veldnamen komen 1-op-1 uit het DTO dat de backend serialiseert
    // (newsfeedbackend/.../feed/api/dto/FeedItemDto.kt). `summary`, `isRead` en
    // `isSummary` staan daar met een expliciete @JsonProperty omdat Jackson ze
    // anders hernoemt; ze staan hier daarom hardgecodeerd in de testdata.
    // `createdAt` is backend-side een Instant en komt als ISO-8601-string over
    // de lijn.
    test('fromJson leest een volledige payload veld voor veld uit', () {
      final item = FeedItem.fromJson({
        'id': 'feed-1',
        'title': 'Original English title',
        'titleNl': 'Nederlandse titel',
        'summary': 'De uitgebreide Nederlandse samenvatting.',
        'shortSummary': 'De korte samenvatting.',
        'url': 'https://example.com/artikel',
        'category': 'technologie',
        'source': 'Example News',
        'sourceRssIds': ['rss-1', 'rss-2'],
        'sourceUrls': ['https://example.com/a', 'https://example.com/b'],
        'topics': ['ai', 'kotlin'],
        'feedReason': 'Past bij je interesse in AI.',
        'isRead': true,
        'starred': true,
        'liked': true,
        'createdAt': '2026-08-22T08:20:24Z',
        'publishedDate': '2026-08-21',
        'isSummary': true,
        'mediaType': 'PODCAST',
        'imageUrl': 'https://example.com/plaatje.png',
      });

      expect(item.id, 'feed-1');
      expect(item.title, 'Original English title');
      expect(item.titleNl, 'Nederlandse titel');
      expect(item.summary, 'De uitgebreide Nederlandse samenvatting.');
      expect(item.shortSummary, 'De korte samenvatting.');
      expect(item.url, 'https://example.com/artikel');
      expect(item.category, 'technologie');
      expect(item.source, 'Example News');
      expect(item.sourceRssIds, ['rss-1', 'rss-2']);
      expect(item.sourceUrls, ['https://example.com/a', 'https://example.com/b']);
      expect(item.topics, ['ai', 'kotlin']);
      expect(item.feedReason, 'Past bij je interesse in AI.');
      expect(item.isRead, isTrue);
      expect(item.starred, isTrue);
      expect(item.liked, isTrue);
      expect(item.createdAt, '2026-08-22T08:20:24Z');
      expect(item.publishedDate, '2026-08-21');
      expect(item.isSummary, isTrue);
      expect(item.mediaType, 'PODCAST');
      expect(item.imageUrl, 'https://example.com/plaatje.png');
    });

    test('fromJson met een lege payload valt terug op de defaults', () {
      final item = FeedItem.fromJson({});

      expect(item.id, '');
      expect(item.title, '');
      expect(item.titleNl, '');
      expect(item.summary, '');
      expect(item.shortSummary, '');
      expect(item.category, 'overig');
      expect(item.source, '');
      expect(item.sourceRssIds, isEmpty);
      expect(item.sourceUrls, isEmpty);
      expect(item.topics, isEmpty);
      expect(item.feedReason, '');
      expect(item.isRead, isFalse);
      expect(item.starred, isFalse);
      expect(item.createdAt, '');
      expect(item.publishedDate, isNull);
      expect(item.isSummary, isFalse);
      expect(item.mediaType, 'ARTICLE');
      // Deze drie hebben bewust géén terugval en blijven dus null.
      expect(item.url, isNull);
      expect(item.liked, isNull);
      expect(item.imageUrl, isNull);
    });

    test('listPreview geeft shortSummary terug zodra die gevuld is', () {
      final item = FeedItem(
        id: 'feed-1',
        title: 'Titel',
        summary: 'De lange samenvatting die niet getoond mag worden.',
        shortSummary: 'De korte samenvatting.',
      );

      expect(item.listPreview, 'De korte samenvatting.');
    });

    test('listPreview valt zonder shortSummary terug op summary met '
        'samengevouwen en getrimde witruimte', () {
      final item = FeedItem(
        id: 'feed-1',
        title: 'Titel',
        summary: '  Eerste regel.\n\n  Tweede\tregel.  ',
      );

      expect(item.listPreview, 'Eerste regel. Tweede regel.');
    });

    // copyWith kent voor `liked` drie waarden (true/false/null) en gebruikt
    // daarom een sentinel als default: alleen zo is "niet meegegeven" te
    // onderscheiden van "expliciet op null gezet". data_providers.dart:92 en
    // :154 leunen daarop voor de optimistische UI-update van de duim-knoppen.
    test('copyWith laat liked ongemoeid als het niet wordt meegegeven', () {
      final item = FeedItem(
        id: 'feed-1',
        title: 'Titel',
        summary: 'Samenvatting',
        liked: true,
      );

      final updated = item.copyWith(isRead: true);

      expect(updated.isRead, isTrue);
      expect(updated.liked, isTrue);
    });

    test('copyWith(liked: false) zet liked op false', () {
      final item = FeedItem(
        id: 'feed-1',
        title: 'Titel',
        summary: 'Samenvatting',
        liked: true,
      );

      expect(item.copyWith(liked: false).liked, isFalse);
    });

    test('copyWith(liked: null) zet liked écht op null', () {
      final item = FeedItem(
        id: 'feed-1',
        title: 'Titel',
        summary: 'Samenvatting',
        liked: true,
      );

      // `liked: null` staat hier bewust letterlijk in de aanroep: via een
      // tussenvariabele zou de sentinel-default weer opduiken en zou deze test
      // de sentinel-constructie niet meer bewaken.
      expect(item.copyWith(liked: null).liked, isNull);
    });
  });

  group('RssItem', () {
    test('fromJson met een lege payload valt terug op de defaults', () {
      final item = RssItem.fromJson({});

      expect(item.id, '');
      expect(item.title, '');
      expect(item.summary, '');
      expect(item.url, '');
      expect(item.category, 'overig');
      expect(item.feedUrl, '');
      expect(item.source, '');
      expect(item.snippet, '');
      expect(item.publishedDate, isNull);
      expect(item.timestamp, '');
      expect(item.inFeed, isFalse);
      expect(item.feedReason, '');
      expect(item.isRead, isFalse);
      expect(item.starred, isFalse);
      expect(item.liked, isNull);
      expect(item.topics, isEmpty);
      expect(item.feedItemId, isNull);
      expect(item.mediaType, 'ARTICLE');
      expect(item.audioUrl, '');
      expect(item.durationSeconds, isNull);
      expect(item.summarySource, 'transcript');
      expect(item.longSummary, isNull);
      expect(item.keyTakeaways, isEmpty);
      expect(item.imageUrl, isNull);
    });

    // Letterlijk dezelfde sentinel-constructie als bij FeedItem (models.dart:225)
    // en dus dezelfde valkuil bij een "opruimende" refactor.
    test('copyWith(liked: null) zet liked écht op null', () {
      final item = RssItem(
        id: 'rss-1',
        title: 'Titel',
        url: 'https://example.com/artikel',
        liked: true,
      );

      expect(item.copyWith(liked: null).liked, isNull);
      // Tegenproef: zonder liked-argument blijft de bestaande waarde staan.
      expect(item.copyWith(isRead: true).liked, isTrue);
    });
  });
}
