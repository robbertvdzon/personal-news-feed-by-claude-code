import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:url_launcher/url_launcher.dart';
import 'api_client.dart';
import 'local_store.dart';
import 'models.dart';
import 'time_format.dart';

final ReadStore readStore = ReadStore();
final ApiClient api = ApiClient();

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await readStore.load();
  runApp(const ReaderApp());
}

class ReaderApp extends StatelessWidget {
  const ReaderApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: "Robbert's News Reader",
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF00897B)),
        useMaterial3: true,
      ),
      home: const FeedListScreen(),
    );
  }
}

/// Bron-filter (mediatype) net als in de grote frontend.
enum _MediaFilter { all, rss, podcasts }

/// Vaste tab-id's voor de categorie-balk.
const String _allTabId = '__all__';
const String _starredTabId = '__starred__';
const String _summaryTabId = '__summary__';

class _Tab {
  final String id;
  final String name;
  const _Tab(this.id, this.name);
}

class FeedListScreen extends StatefulWidget {
  const FeedListScreen({super.key});

  @override
  State<FeedListScreen> createState() => _FeedListScreenState();
}

class _FeedListScreenState extends State<FeedListScreen> {
  List<FeedItem> _all = const [];
  List<CategorySettings> _cats = const [];
  bool _loading = true;
  Object? _error;
  _MediaFilter _mediaFilter = _MediaFilter.all;
  String _selectedTab = _allTabId;
  bool _hideRead = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// Laadt feed + categorieën in de state. Categorieën falen zacht (lege
  /// lijst) zodat de feed ook werkt zonder het categorie-endpoint.
  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final feed = await api.fetchFeed();
      List<CategorySettings> cats = const [];
      try {
        cats = await api.fetchCategories();
      } catch (_) {}
      if (!mounted) return;
      setState(() {
        _all = feed;
        _cats = cats;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e;
        _loading = false;
      });
    }
  }

  bool _matchesTab(FeedItem it, String tabId) {
    switch (tabId) {
      case _allTabId:
        return true;
      case _starredTabId:
        return readStore.isStarred(it.id);
      case _summaryTabId:
        return it.isSummary;
      default:
        return it.category == tabId;
    }
  }

  bool _matchesMedia(FeedItem it) {
    switch (_mediaFilter) {
      case _MediaFilter.all:
        return true;
      case _MediaFilter.rss:
        return !it.isPodcast;
      case _MediaFilter.podcasts:
        return it.isPodcast;
    }
  }

  /// Telt items voor een specifieke tab, inclusief het actieve bron-filter
  /// en de verberg-gelezen-toggle (context-afhankelijk, net als de grote app).
  int _countFor(List<FeedItem> items, String tabId) {
    return items.where((it) {
      if (!_matchesTab(it, tabId)) return false;
      if (!_matchesMedia(it)) return false;
      if (_hideRead && readStore.isRead(it.id)) return false;
      return true;
    }).length;
  }

  List<FeedItem> _visible(List<FeedItem> items) {
    return items.where((it) {
      if (!_matchesTab(it, _selectedTab)) return false;
      if (!_matchesMedia(it)) return false;
      if (_hideRead && readStore.isRead(it.id)) return false;
      return true;
    }).toList();
  }

  List<_Tab> _tabs() => [
        const _Tab(_allTabId, 'Alles'),
        const _Tab(_starredTabId, 'Bewaard'),
        const _Tab(_summaryTabId, 'Samenvatting'),
        ..._cats.map((c) => _Tab(c.id, c.name)),
      ];

  Future<void> _confirmMarkAllRead(List<FeedItem> all) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Markeer alles als gelezen?'),
        content: const Text(
          'Alle items in de feed worden als gelezen aangemerkt. Je kunt dit '
          'per item terugdraaien in het detail-scherm.',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Annuleren')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Alles als gelezen')),
        ],
      ),
    );
    if (ok == true) {
      final n = readStore.markAllRead(all.map((e) => e.id));
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('$n item(s) als gelezen gemarkeerd')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Nieuwsfeed'),
        actions: [
          IconButton(
            tooltip: 'Markeer alles als gelezen',
            icon: const Icon(Icons.done_all),
            // Donkere kleur voor sterk contrast op de lichte appbar, zodat
            // de knop duidelijk te onderscheiden is van het herlaad-icoon.
            color: const Color(0xFF004D40),
            onPressed: _all.isEmpty ? null : () => _confirmMarkAllRead(_all),
          ),
          IconButton(
            tooltip: 'Herladen',
            icon: const Icon(Icons.refresh),
            onPressed: _load,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? _ErrorView(onRetry: _load, error: '$_error')
              : ListenableBuilder(
                  listenable: readStore,
                  builder: (context, _) {
                    final tabs = _tabs();
                    if (!tabs.any((t) => t.id == _selectedTab)) {
                      _selectedTab = _allTabId;
                    }
                    final items = _visible(_all);
                    return Column(
                      children: [
                        _ControlBar(
                          mediaFilter: _mediaFilter,
                          onMediaChanged: (f) => setState(() => _mediaFilter = f),
                          hideRead: _hideRead,
                          onHideReadChanged: (v) => setState(() => _hideRead = v),
                        ),
                        _CategoryTabBar(
                          tabs: tabs,
                          selectedId: _selectedTab,
                          countFor: (id) => _countFor(_all, id),
                          onSelected: (id) => setState(() => _selectedTab = id),
                        ),
                        const Divider(height: 1),
                        Expanded(
                          child: RefreshIndicator(
                            onRefresh: _load,
                            child: items.isEmpty
                                ? ListView(
                                    children: const [
                                      SizedBox(height: 120),
                                      Center(child: Text('Geen items')),
                                    ],
                                  )
                                : ListView.builder(
                                    itemCount: items.length,
                                    itemBuilder: (ctx, i) => ReaderCard(
                                      item: items[i],
                                      onTap: () => _open(items, i),
                                    ),
                                  ),
                          ),
                        ),
                      ],
                    );
                  },
                ),
    );
  }

  void _open(List<FeedItem> items, int idx) {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => DetailScreen(items: items, initialIndex: idx),
    ));
  }
}

/// Compacte controlebalk: bron-filter als dropdown (past op mobiel) +
/// verberg-gelezen-toggle.
class _ControlBar extends StatelessWidget {
  final _MediaFilter mediaFilter;
  final ValueChanged<_MediaFilter> onMediaChanged;
  final bool hideRead;
  final ValueChanged<bool> onHideReadChanged;

  const _ControlBar({
    required this.mediaFilter,
    required this.onMediaChanged,
    required this.hideRead,
    required this.onHideReadChanged,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
      // SizedBox(full width) zodat de Wrap de volledige breedte vult en
      // spaceBetween de dropdown links en de verberg-gelezen-toggle rechts
      // zet. Op smalle (mobiele) schermen zakt de toggle volledig zichtbaar
      // naar de volgende regel i.p.v. afgekapt te worden.
      child: SizedBox(
        width: double.infinity,
        child: Wrap(
        spacing: 12,
        runSpacing: 4,
        alignment: WrapAlignment.spaceBetween,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            decoration: BoxDecoration(
              border: Border.all(color: theme.colorScheme.outlineVariant),
              borderRadius: BorderRadius.circular(20),
            ),
            child: DropdownButtonHideUnderline(
              child: DropdownButton<_MediaFilter>(
                value: mediaFilter,
                isDense: true,
                borderRadius: BorderRadius.circular(12),
                onChanged: (v) {
                  if (v != null) onMediaChanged(v);
                },
                items: const [
                  DropdownMenuItem(
                    value: _MediaFilter.all,
                    child: _DropRow(icon: Icons.dynamic_feed, label: 'Alles'),
                  ),
                  DropdownMenuItem(
                    value: _MediaFilter.rss,
                    child: _DropRow(icon: Icons.rss_feed, label: 'RSS'),
                  ),
                  DropdownMenuItem(
                    value: _MediaFilter.podcasts,
                    child: _DropRow(icon: Icons.podcasts, label: 'Podcasts'),
                  ),
                ],
              ),
            ),
          ),
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('Verberg gelezen', style: theme.textTheme.bodyMedium),
              Switch(value: hideRead, onChanged: onHideReadChanged),
            ],
          ),
        ],
        ),
      ),
    );
  }
}

/// Eén regel in de bron-dropdown: icoon + label.
class _DropRow extends StatelessWidget {
  final IconData icon;
  final String label;
  const _DropRow({required this.icon, required this.label});

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 18),
        const SizedBox(width: 8),
        Text(label),
      ],
    );
  }
}

/// Horizontaal scrollende categorie-tabjes met telling-bolletjes.
class _CategoryTabBar extends StatelessWidget {
  final List<_Tab> tabs;
  final String selectedId;
  final int Function(String tabId) countFor;
  final ValueChanged<String> onSelected;

  const _CategoryTabBar({
    required this.tabs,
    required this.selectedId,
    required this.countFor,
    required this.onSelected,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return SizedBox(
      height: 48,
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(
          children: tabs.map((t) {
            final selected = t.id == selectedId;
            final count = countFor(t.id);
            return InkWell(
              onTap: () => onSelected(t.id),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          t.name,
                          style: TextStyle(
                            color: selected
                                ? theme.colorScheme.primary
                                : theme.textTheme.bodyMedium?.color,
                            fontWeight: selected ? FontWeight.bold : FontWeight.normal,
                          ),
                        ),
                        if (count > 0) ...[
                          const SizedBox(width: 6),
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                            decoration: BoxDecoration(
                              color: selected
                                  ? theme.colorScheme.primary
                                  : theme.colorScheme.surfaceContainerHighest,
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: Text(
                              '$count',
                              style: TextStyle(
                                fontSize: 12,
                                color: selected
                                    ? theme.colorScheme.onPrimary
                                    : theme.colorScheme.onSurfaceVariant,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                        ],
                      ],
                    ),
                    const SizedBox(height: 4),
                    Container(
                      height: 3,
                      width: 28,
                      decoration: BoxDecoration(
                        color: selected ? theme.colorScheme.primary : Colors.transparent,
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                  ],
                ),
              ),
            );
          }).toList(),
        ),
      ),
    );
  }
}

class ReaderCard extends StatelessWidget {
  final FeedItem item;
  final VoidCallback onTap;

  const ReaderCard({super.key, required this.item, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final isRead = readStore.isRead(item.id);
    final starred = readStore.isStarred(item.id);
    final theme = Theme.of(context);
    return Card(
      child: ListTile(
        onTap: onTap,
        title: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Bron-icoontje: duidelijk of het een podcast of RSS-artikel is.
            Padding(
              padding: const EdgeInsets.only(top: 2, right: 6),
              child: Icon(
                item.isPodcast ? Icons.podcasts : Icons.rss_feed,
                size: 18,
                color: theme.colorScheme.primary,
              ),
            ),
            Expanded(
              child: Text(
                item.displayTitle,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontWeight: isRead ? FontWeight.normal : FontWeight.bold,
                  color: isRead ? theme.hintColor : null,
                ),
              ),
            ),
          ],
        ),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SizedBox(height: 4),
            Wrap(spacing: 8, runSpacing: 2, crossAxisAlignment: WrapCrossAlignment.center, children: [
              if (item.source.isNotEmpty)
                Text(item.source, style: theme.textTheme.bodySmall),
              if (formatRelativeTime(item.createdAt).isNotEmpty)
                Text('· ${formatRelativeTime(item.createdAt)}',
                    style: theme.textTheme.bodySmall?.copyWith(color: theme.hintColor)),
              Chip(
                label: Text(item.category),
                visualDensity: VisualDensity.compact,
                materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
              if (item.isSummary) const Chip(label: Text('Samenvatting')),
            ]),
            const SizedBox(height: 4),
            Text(item.listPreview, maxLines: 3, overflow: TextOverflow.ellipsis),
            Align(
              alignment: Alignment.centerRight,
              child: IconButton(
                tooltip: starred ? 'Verwijder uit bewaard' : 'Bewaar',
                icon: Icon(starred ? Icons.star : Icons.star_outline,
                    color: starred ? Colors.amber : null),
                onPressed: () => readStore.toggleStar(item.id),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class DetailScreen extends StatefulWidget {
  final List<FeedItem> items;
  final int initialIndex;

  const DetailScreen({super.key, required this.items, required this.initialIndex});

  @override
  State<DetailScreen> createState() => _DetailScreenState();
}

class _DetailScreenState extends State<DetailScreen> {
  late PageController _ctrl;
  late int _idx;

  @override
  void initState() {
    super.initState();
    _idx = widget.initialIndex;
    _ctrl = PageController(initialPage: widget.initialIndex);
    readStore.markRead(widget.items[_idx].id);
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final current = widget.items[_idx];
    return Scaffold(
      appBar: AppBar(
        title: Text('${_idx + 1}/${widget.items.length}'),
        actions: [
          ListenableBuilder(
            listenable: readStore,
            builder: (context, _) {
              final starred = readStore.isStarred(current.id);
              final isRead = readStore.isRead(current.id);
              return Row(children: [
                IconButton(
                  tooltip: starred ? 'Verwijder uit bewaard' : 'Bewaar',
                  icon: Icon(starred ? Icons.star : Icons.star_outline,
                      color: starred ? Colors.amber : null),
                  onPressed: () => readStore.toggleStar(current.id),
                ),
                IconButton(
                  tooltip: isRead ? 'Markeer als ongelezen' : 'Markeer als gelezen',
                  icon: Icon(isRead
                      ? Icons.mark_email_unread_outlined
                      : Icons.mark_email_read_outlined),
                  onPressed: () => readStore.setRead(current.id, !isRead),
                ),
              ]);
            },
          ),
        ],
      ),
      body: PageView.builder(
        controller: _ctrl,
        itemCount: widget.items.length,
        onPageChanged: (i) {
          setState(() => _idx = i);
          readStore.markRead(widget.items[i].id);
        },
        itemBuilder: (ctx, i) => _itemView(widget.items[i]),
      ),
    );
  }

  Widget _itemView(FeedItem it) {
    final bottomInset = MediaQuery.of(context).padding.bottom;
    return SingleChildScrollView(
      padding: EdgeInsets.fromLTRB(16, 16, 16, 16 + bottomInset),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.only(top: 4, right: 8),
                child: Icon(
                  it.isPodcast ? Icons.podcasts : Icons.rss_feed,
                  color: Theme.of(context).colorScheme.primary,
                ),
              ),
              Expanded(
                child: Text(it.displayTitle, style: Theme.of(context).textTheme.headlineSmall),
              ),
            ],
          ),
          if (it.titleNl.isNotEmpty && it.titleNl != it.title) ...[
            const SizedBox(height: 4),
            Text(it.title,
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).hintColor,
                      fontStyle: FontStyle.italic,
                    )),
          ],
          const SizedBox(height: 8),
          Wrap(spacing: 8, children: [
            if (it.source.isNotEmpty) Chip(label: Text(it.source)),
            Chip(label: Text(it.category)),
            if (it.publishedDate != null) Chip(label: Text(it.publishedDate!)),
          ]),
          const SizedBox(height: 16),
          MarkdownBody(data: it.summary, selectable: true),
          const SizedBox(height: 24),
          if (it.url != null)
            FilledButton.icon(
              onPressed: () =>
                  launchUrl(Uri.parse(it.url!), mode: LaunchMode.externalApplication),
              icon: const Icon(Icons.open_in_new),
              label: const Text('Open bron'),
            ),
        ],
      ),
    );
  }
}

class _ErrorView extends StatelessWidget {
  final VoidCallback onRetry;
  final String error;

  const _ErrorView({required this.onRetry, required this.error});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.cloud_off, size: 48),
            const SizedBox(height: 12),
            Text('Kon de feed niet laden.\n$error', textAlign: TextAlign.center),
            const SizedBox(height: 16),
            FilledButton(onPressed: onRetry, child: const Text('Opnieuw proberen')),
          ],
        ),
      ),
    );
  }
}
