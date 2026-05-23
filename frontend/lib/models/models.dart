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
  /// KAN-60: 'ARTICLE' (default) of 'PODCAST'. Stuurt de Feed-tab filter
  /// (AC8) en evt. een podcasts-icoon op de feed-card.
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
    this.mediaType = 'ARTICLE',
  });

  bool get isPodcast => mediaType == 'PODCAST';

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
        mediaType: j['mediaType'] ?? 'ARTICLE',
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
        mediaType: mediaType,
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
  /// KAN-56: discriminator voor de RSS-tab. 'ARTICLE' (default) of 'PODCAST'.
  final String mediaType;
  /// Bij PODCAST: directe MP3-URL voor 'Origineel afspelen'.
  final String audioUrl;
  /// Bij PODCAST: lengte in seconden (vaak uit `<itunes:duration>`).
  final int? durationSeconds;
  /// KAN-60: 'show_notes' = voorlopige samenvatting op RSS-description,
  /// 'transcript' = échte Whisper-transcript-summary. Wordt door
  /// [isShowNotesBased] omgezet naar de badge in de UI.
  final String summarySource;
  /// KAN-62: lange Nederlandse samenvatting (~400-600 woorden, 3-5
  /// alinea's) voor het podcast-detail-scherm. Leeg/null voor (a) niet-
  /// podcast items en (b) podcasts die nog niet door de uitgebreide
  /// Claude-prompt heen zijn — detail-scherm valt dan terug op [summary].
  final String? longSummary;
  /// KAN-62: 5-10 concrete takeaway-bullets uit de aflevering. Lege
  /// lijst = sectie wordt verborgen op het detail-scherm.
  final List<String> keyTakeaways;

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
    this.mediaType = 'ARTICLE',
    this.audioUrl = '',
    this.durationSeconds,
    this.summarySource = 'transcript',
    this.longSummary,
    this.keyTakeaways = const [],
  });

  bool get isPodcast => mediaType == 'PODCAST';
  /// KAN-60: true wanneer de card nog een voorlopige (show-notes-)
  /// samenvatting toont en de transcript-fase nog niet gedraaid is.
  bool get isShowNotesBased => isPodcast && summarySource == 'show_notes';

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
        mediaType: j['mediaType'] ?? 'ARTICLE',
        audioUrl: j['audioUrl'] ?? '',
        durationSeconds: j['durationSeconds'],
        summarySource: j['summarySource'] ?? 'transcript',
        longSummary: j['longSummary'],
        keyTakeaways: List<String>.from(j['keyTakeaways'] ?? const []),
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
        mediaType: mediaType,
        audioUrl: audioUrl,
        durationSeconds: durationSeconds,
        summarySource: summarySource,
        longSummary: longSummary,
        keyTakeaways: keyTakeaways,
      );
}

/// KAN-56: één podcast-bron (RSS-URL + per-bron transcribe-toggle).
class PodcastFeed {
  final String url;
  final bool transcribeEnabled;

  PodcastFeed({required this.url, this.transcribeEnabled = true});

  factory PodcastFeed.fromJson(Map<String, dynamic> j) => PodcastFeed(
        url: j['url'] ?? '',
        transcribeEnabled: j['transcribeEnabled'] ?? true,
      );

  Map<String, dynamic> toJson() => {
        'url': url,
        'transcribeEnabled': transcribeEnabled,
      };

  PodcastFeed copyWith({String? url, bool? transcribeEnabled}) => PodcastFeed(
        url: url ?? this.url,
        transcribeEnabled: transcribeEnabled ?? this.transcribeEnabled,
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
  final List<String> customTopics;
  final String ttsProvider;
  final int podcastNumber;
  final int? generationSeconds;
  /// KAN-63: bron-aflevering-guid bij een podcast die een NL-vertaling
  /// is van een RSS-podcast-aflevering. Null voor de zelf-gegenereerde
  /// DevTalk-podcasts.
  final String? translatedFromEpisodeGuid;
  final String? translatedFromFeedUrl;
  /// Display-naam van de bron-podcast voor de "vertaald van X"-badge.
  final String? translatedFromFeedName;
  final String? translatedFromEpisodeTitle;
  /// rss_items.id van de bron-aflevering — voor de tap-actie op de badge.
  final String? translatedFromRssItemId;
  /// Bij status=FAILED: korte foutomschrijving.
  final String? errorMessage;

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
    this.customTopics = const [],
    this.ttsProvider = 'OPENAI',
    this.podcastNumber = 0,
    this.generationSeconds,
    this.translatedFromEpisodeGuid,
    this.translatedFromFeedUrl,
    this.translatedFromFeedName,
    this.translatedFromEpisodeTitle,
    this.translatedFromRssItemId,
    this.errorMessage,
  });

  bool get isTranslation => translatedFromEpisodeGuid != null;

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
        customTopics: List<String>.from(j['customTopics'] ?? []),
        ttsProvider: j['ttsProvider'] ?? 'OPENAI',
        podcastNumber: j['podcastNumber'] ?? 0,
        generationSeconds: j['generationSeconds'],
        translatedFromEpisodeGuid: j['translatedFromEpisodeGuid'],
        translatedFromFeedUrl: j['translatedFromFeedUrl'],
        translatedFromFeedName: j['translatedFromFeedName'],
        translatedFromEpisodeTitle: j['translatedFromEpisodeTitle'],
        translatedFromRssItemId: j['translatedFromRssItemId'],
        errorMessage: j['errorMessage'],
      );
}

/// KAN-63: respons van `GET /api/podcast-source/by-rss-item/{rssItemId}`.
/// Wordt door [RssPodcastDetailScreen] gefetcht bij openen om te bepalen
/// of de translate-knop "Vertaal & genereer" of "Bekijk vertaling" moet
/// tonen, en om de kosten-schatting te berekenen.
class EpisodeLookup {
  final String episodeGuid;
  final String episodeTitle;
  final String episodeStatus;
  final int transcriptCharCount;
  final String feedUrl;
  final String feedName;
  final String? rssItemId;
  final String? translatedPodcastId;
  final String? translatedPodcastStatus;
  final String? translatedPodcastTitle;
  final String? translatedPodcastErrorMessage;

  EpisodeLookup({
    required this.episodeGuid,
    this.episodeTitle = '',
    this.episodeStatus = '',
    this.transcriptCharCount = 0,
    this.feedUrl = '',
    this.feedName = '',
    this.rssItemId,
    this.translatedPodcastId,
    this.translatedPodcastStatus,
    this.translatedPodcastTitle,
    this.translatedPodcastErrorMessage,
  });

  factory EpisodeLookup.fromJson(Map<String, dynamic> j) => EpisodeLookup(
        episodeGuid: j['episodeGuid'] ?? '',
        episodeTitle: j['episodeTitle'] ?? '',
        episodeStatus: j['episodeStatus'] ?? '',
        transcriptCharCount: j['transcriptCharCount'] ?? 0,
        feedUrl: j['feedUrl'] ?? '',
        feedName: j['feedName'] ?? '',
        rssItemId: j['rssItemId'],
        translatedPodcastId: j['translatedPodcastId'],
        translatedPodcastStatus: j['translatedPodcastStatus'],
        translatedPodcastTitle: j['translatedPodcastTitle'],
        translatedPodcastErrorMessage: j['translatedPodcastErrorMessage'],
      );

  /// True als er nog geen translate-poging gedaan is.
  bool get hasNoTranslation => translatedPodcastId == null;

  /// True wanneer een eerdere translate-poging op FAILED is geëindigd.
  bool get translationFailed => translatedPodcastStatus == 'FAILED';

  /// True wanneer de vertaling klaar is en de "Bekijk vertaling"-knop
  /// naar de podcast-detail-screen mag navigeren.
  bool get translationDone => translatedPodcastStatus == 'DONE';

  /// True wanneer de vertaling op de achtergrond loopt.
  bool get translationInProgress => translatedPodcastStatus != null &&
      const ['PENDING', 'TRANSLATING', 'TTS_GENERATING'].contains(translatedPodcastStatus);
}

/// KAN-65: een ontdekt tech-event. Wekelijks per gebruiker ontdekt met
/// AI + web-search op basis van de categorie-settings. Getoond in de
/// Events-tab, gesorteerd op datum met onderscheid aankomend/geweest.
class Event {
  final String id;
  final String name;
  final String? organization;
  /// Begindatum YYYY-MM-DD; null wanneer onbekend.
  final String? startDate;
  final String? endDate;
  final String location;
  final String description;
  final List<String> sourceLinks;
  final String category;
  final String? feedItemId;

  Event({
    required this.id,
    required this.name,
    this.organization,
    this.startDate,
    this.endDate,
    this.location = '',
    this.description = '',
    this.sourceLinks = const [],
    this.category = 'overig',
    this.feedItemId,
  });

  /// True wanneer de begindatum vandaag of in de toekomst ligt. Events
  /// zonder datum behandelen we als aankomend (gebruiker beslist).
  bool get isUpcoming {
    final d = startDate;
    if (d == null || d.isEmpty) return true;
    final parsed = DateTime.tryParse(d);
    if (parsed == null) return true;
    final today = DateTime.now();
    final midnight = DateTime(today.year, today.month, today.day);
    return !parsed.isBefore(midnight);
  }

  factory Event.fromJson(Map<String, dynamic> j) => Event(
        id: j['id'] ?? '',
        name: j['name'] ?? '',
        organization: j['organization'],
        startDate: j['startDate'],
        endDate: j['endDate'],
        location: j['location'] ?? '',
        description: j['description'] ?? '',
        sourceLinks: List<String>.from(j['sourceLinks'] ?? const []),
        category: j['category'] ?? 'overig',
        feedItemId: j['feedItemId'],
      );
}

/// KAN-66: één online video (keynote/sessie) van een [Event]. Wekelijks
/// ontdekt met AI + web-search.
///
/// KAN-67: voegt [summaryNl] toe — een Nederlandse on-demand samenvatting
/// (transcript + Claude). Null tot de gebruiker op "Maak samenvatting"
/// drukt; daarna voor altijd aanwezig (server-side gecachet).
class EventVideo {
  final String eventId;
  final String videoUrl;
  final String title;
  final String? descriptionNl;
  final String? summaryNl;

  EventVideo({
    required this.eventId,
    required this.videoUrl,
    required this.title,
    this.descriptionNl,
    this.summaryNl,
  });

  EventVideo copyWith({String? summaryNl}) => EventVideo(
        eventId: eventId,
        videoUrl: videoUrl,
        title: title,
        descriptionNl: descriptionNl,
        summaryNl: summaryNl ?? this.summaryNl,
      );

  factory EventVideo.fromJson(Map<String, dynamic> j) => EventVideo(
        eventId: j['eventId'] ?? '',
        videoUrl: j['videoUrl'] ?? '',
        title: j['title'] ?? '',
        descriptionNl: j['descriptionNl'],
        summaryNl: j['summaryNl'],
      );
}
