class FeedItem {
  final String id;
  /// Originele titel uit de RSS-feed (vaak Engels).
  final String title;
  /// Korte Nederlandse titel — gebruikt op de feed-lijst en als headline.
  /// Leeg voor legacy items: dan terugvallen op [title].
  final String titleNl;
  /// Uitgebreide Nederlandse samenvatting (400-600 woorden, kan markdown bevatten).
  final String summary;
  /// Korte 2-regel Nederlandse samenvatting (plain text, ~30-50 woorden) — feed-lijst.
  /// Leeg voor legacy items: dan terugvallen op de eerste regels van [summary].
  final String shortSummary;
  final String? url;
  final String category;
  final String source;
  final List<String> sourceRssIds;
  final List<String> sourceUrls;
  final List<String> topics;
  final String feedReason;
  final bool isRead;
  final bool starred;
  final bool? liked;
  final String createdAt;
  final String? publishedDate;
  final bool isSummary;

  FeedItem({
    required this.id,
    required this.title,
    this.titleNl = '',
    required this.summary,
    this.shortSummary = '',
    this.url,
    this.category = 'overig',
    this.source = '',
    this.sourceRssIds = const [],
    this.sourceUrls = const [],
    this.topics = const [],
    this.feedReason = '',
    this.isRead = false,
    this.starred = false,
    this.liked,
    this.createdAt = '',
    this.publishedDate,
    this.isSummary = false,
  });

  /// Display-titel voor list/detail. Pakt eerst [titleNl], anders [title].
  String get displayTitle => titleNl.isNotEmpty ? titleNl : title;

  /// 2-regel preview voor de feed-lijst. Pakt eerst [shortSummary],
  /// anders een afgekapte versie van [summary] (legacy fallback).
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
        sourceRssIds: List<String>.from(j['sourceRssIds'] ?? []),
        sourceUrls: List<String>.from(j['sourceUrls'] ?? []),
        topics: List<String>.from(j['topics'] ?? []),
        feedReason: j['feedReason'] ?? '',
        isRead: j['isRead'] ?? false,
        starred: j['starred'] ?? false,
        liked: j['liked'],
        createdAt: j['createdAt'] ?? '',
        publishedDate: j['publishedDate'],
        isSummary: j['isSummary'] ?? false,
      );

  FeedItem copyWith({bool? isRead, bool? starred, Object? liked = const _Sentinel()}) => FeedItem(
        id: id,
        title: title,
        titleNl: titleNl,
        summary: summary,
        shortSummary: shortSummary,
        url: url,
        category: category,
        source: source,
        sourceRssIds: sourceRssIds,
        sourceUrls: sourceUrls,
        topics: topics,
        feedReason: feedReason,
        isRead: isRead ?? this.isRead,
        starred: starred ?? this.starred,
        liked: liked is _Sentinel ? this.liked : liked as bool?,
        createdAt: createdAt,
        publishedDate: publishedDate,
        isSummary: isSummary,
      );
}

class _Sentinel { const _Sentinel(); }

class RssItem {
  final String id;
  final String title;
  final String summary;
  final String url;
  final String category;
  final String feedUrl;
  final String source;
  final String snippet;
  final String? publishedDate;
  final String timestamp;
  final bool inFeed;
  final String feedReason;
  final bool isRead;
  final bool starred;
  final bool? liked;
  final List<String> topics;
  final String? feedItemId;

  RssItem({
    required this.id,
    required this.title,
    this.summary = '',
    required this.url,
    this.category = 'overig',
    this.feedUrl = '',
    this.source = '',
    this.snippet = '',
    this.publishedDate,
    this.timestamp = '',
    this.inFeed = false,
    this.feedReason = '',
    this.isRead = false,
    this.starred = false,
    this.liked,
    this.topics = const [],
    this.feedItemId,
  });

  factory RssItem.fromJson(Map<String, dynamic> j) => RssItem(
        id: j['id'] ?? '',
        title: j['title'] ?? '',
        summary: j['summary'] ?? '',
        url: j['url'] ?? '',
        category: j['category'] ?? 'overig',
        feedUrl: j['feedUrl'] ?? '',
        source: j['source'] ?? '',
        snippet: j['snippet'] ?? '',
        publishedDate: j['publishedDate'],
        timestamp: j['timestamp'] ?? '',
        inFeed: j['inFeed'] ?? false,
        feedReason: j['feedReason'] ?? '',
        isRead: j['isRead'] ?? false,
        starred: j['starred'] ?? false,
        liked: j['liked'],
        topics: List<String>.from(j['topics'] ?? []),
        feedItemId: j['feedItemId'],
      );

  RssItem copyWith({bool? isRead, bool? starred, Object? liked = const _Sentinel()}) => RssItem(
        id: id,
        title: title,
        summary: summary,
        url: url,
        category: category,
        feedUrl: feedUrl,
        source: source,
        snippet: snippet,
        publishedDate: publishedDate,
        timestamp: timestamp,
        inFeed: inFeed,
        feedReason: feedReason,
        isRead: isRead ?? this.isRead,
        starred: starred ?? this.starred,
        liked: liked is _Sentinel ? this.liked : liked as bool?,
        topics: topics,
        feedItemId: feedItemId,
      );
}

class CategorySettings {
  final String id;
  final String name;
  final bool enabled;
  final String extraInstructions;
  final bool isSystem;

  CategorySettings({
    required this.id,
    required this.name,
    this.enabled = true,
    this.extraInstructions = '',
    this.isSystem = false,
  });

  factory CategorySettings.fromJson(Map<String, dynamic> j) => CategorySettings(
        id: j['id'] ?? '',
        name: j['name'] ?? '',
        enabled: j['enabled'] ?? true,
        extraInstructions: j['extraInstructions'] ?? '',
        isSystem: j['isSystem'] ?? false,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'enabled': enabled,
        'extraInstructions': extraInstructions,
        'isSystem': isSystem,
      };

  CategorySettings copyWith({String? name, bool? enabled, String? extraInstructions}) =>
      CategorySettings(
        id: id,
        name: name ?? this.name,
        enabled: enabled ?? this.enabled,
        extraInstructions: extraInstructions ?? this.extraInstructions,
        isSystem: isSystem,
      );
}

class NewsRequest {
  final String id;
  final String subject;
  final String? sourceItemId;
  final String? sourceItemTitle;
  final int newItemCount;
  final double costUsd;
  final bool isHourlyUpdate;
  final bool isDailySummary;
  final String status;
  final String? completedAt;
  final String createdAt;
  final int durationSeconds;
  final List<dynamic> categoryResults;

  NewsRequest({
    required this.id,
    required this.subject,
    this.sourceItemId,
    this.sourceItemTitle,
    this.newItemCount = 0,
    this.costUsd = 0,
    this.isHourlyUpdate = false,
    this.isDailySummary = false,
    this.status = 'PENDING',
    this.completedAt,
    this.createdAt = '',
    this.durationSeconds = 0,
    this.categoryResults = const [],
  });

  factory NewsRequest.fromJson(Map<String, dynamic> j) => NewsRequest(
        id: j['id'] ?? '',
        subject: j['subject'] ?? '',
        sourceItemId: j['sourceItemId'],
        sourceItemTitle: j['sourceItemTitle'],
        newItemCount: j['newItemCount'] ?? 0,
        costUsd: (j['costUsd'] ?? 0).toDouble(),
        isHourlyUpdate: j['isHourlyUpdate'] ?? j['isDailyUpdate'] ?? false,
        isDailySummary: j['isDailySummary'] ?? false,
        status: j['status'] ?? 'PENDING',
        completedAt: j['completedAt'],
        createdAt: j['createdAt'] ?? '',
        durationSeconds: j['durationSeconds'] ?? 0,
        categoryResults: List<dynamic>.from(j['categoryResults'] ?? []),
      );

  NewsRequest copyWith({String? status}) => NewsRequest(
        id: id,
        subject: subject,
        sourceItemId: sourceItemId,
        sourceItemTitle: sourceItemTitle,
        newItemCount: newItemCount,
        costUsd: costUsd,
        isHourlyUpdate: isHourlyUpdate,
        isDailySummary: isDailySummary,
        status: status ?? this.status,
        completedAt: completedAt,
        createdAt: createdAt,
        durationSeconds: durationSeconds,
        categoryResults: categoryResults,
      );
}

class Podcast {
  final String id;
  final String title;
  final String periodDescription;
  final int periodDays;
  final int durationMinutes;
  final String status;
  final String createdAt;
  final String? scriptText;
  final List<String> topics;
  final String? audioPath;
  final int? durationSeconds;
  final double costUsd;
  final List<String> customTopics;
  final String ttsProvider;
  final int podcastNumber;
  final int? generationSeconds;

  Podcast({
    required this.id,
    required this.title,
    this.periodDescription = '',
    this.periodDays = 7,
    this.durationMinutes = 15,
    this.status = 'PENDING',
    this.createdAt = '',
    this.scriptText,
    this.topics = const [],
    this.audioPath,
    this.durationSeconds,
    this.costUsd = 0,
    this.customTopics = const [],
    this.ttsProvider = 'OPENAI',
    this.podcastNumber = 0,
    this.generationSeconds,
  });

  factory Podcast.fromJson(Map<String, dynamic> j) => Podcast(
        id: j['id'] ?? '',
        title: j['title'] ?? '',
        periodDescription: j['periodDescription'] ?? '',
        periodDays: j['periodDays'] ?? 7,
        durationMinutes: j['durationMinutes'] ?? 15,
        status: j['status'] ?? 'PENDING',
        createdAt: j['createdAt'] ?? '',
        scriptText: j['scriptText'],
        topics: List<String>.from(j['topics'] ?? []),
        audioPath: j['audioPath'],
        durationSeconds: j['durationSeconds'],
        costUsd: (j['costUsd'] ?? 0).toDouble(),
        customTopics: List<String>.from(j['customTopics'] ?? []),
        ttsProvider: j['ttsProvider'] ?? 'OPENAI',
        podcastNumber: j['podcastNumber'] ?? 0,
        generationSeconds: j['generationSeconds'],
      );
}
