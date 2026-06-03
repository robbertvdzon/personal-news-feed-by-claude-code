/// Read-only weergave van een feed-item uit `GET /api/shared/feed`.
///
/// Bewust een subset van het backend-model: gelezen/sterretje komen NIET
/// van de server (die geeft schone defaults), maar uit de lokale
/// [ReadStore] op het toestel.
class FeedItem {
  final String id;
  final String title;
  final String titleNl;
  final String summary;
  final String shortSummary;
  final String? url;
  final String category;
  final String source;
  final List<String> topics;
  final String createdAt;
  final String? publishedDate;
  final bool isSummary;
  final String mediaType;

  FeedItem({
    required this.id,
    required this.title,
    this.titleNl = '',
    required this.summary,
    this.shortSummary = '',
    this.url,
    this.category = 'overig',
    this.source = '',
    this.topics = const [],
    this.createdAt = '',
    this.publishedDate,
    this.isSummary = false,
    this.mediaType = 'ARTICLE',
  });

  bool get isPodcast => mediaType == 'PODCAST';

  String get displayTitle => titleNl.isNotEmpty ? titleNl : title;

  String get listPreview =>
      shortSummary.isNotEmpty ? shortSummary : summary.replaceAll(RegExp(r'\s+'), ' ').trim();

  factory FeedItem.fromJson(Map<String, dynamic> j) => FeedItem(
        id: j['id'] ?? '',
        title: j['title'] ?? '',
        titleNl: j['titleNl'] ?? '',
        summary: j['summary'] ?? '',
        shortSummary: j['shortSummary'] ?? '',
        url: j['url'],
        category: j['category'] ?? 'overig',
        source: j['source'] ?? '',
        topics: List<String>.from(j['topics'] ?? const []),
        createdAt: j['createdAt'] ?? '',
        publishedDate: j['publishedDate'],
        isSummary: j['isSummary'] ?? false,
        mediaType: j['mediaType'] ?? 'ARTICLE',
      );
}

/// Categorie-instelling van de bron-gebruiker, opgehaald via
/// `GET /api/shared/categories`. Levert de nette display-naam + volgorde
/// voor de categorie-tabjes (het [id] komt overeen met [FeedItem.category]).
class CategorySettings {
  final String id;
  final String name;
  final bool enabled;

  CategorySettings({required this.id, required this.name, this.enabled = true});

  factory CategorySettings.fromJson(Map<String, dynamic> j) => CategorySettings(
        id: j['id'] ?? '',
        name: j['name'] ?? (j['id'] ?? ''),
        enabled: j['enabled'] ?? true,
      );
}
