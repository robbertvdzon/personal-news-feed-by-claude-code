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

class FeedListScreen extends StatefulWidget {
  const FeedListScreen({super.key});

  @override
  State<FeedListScreen> createState() => _FeedListScreenState();
}

class _FeedListScreenState extends State<FeedListScreen> {
  late Future<List<FeedItem>> _future;
  bool _hideRead = true;
  bool _onlyStarred = false;

  @override
  void initState() {
    super.initState();
    _future = api.fetchFeed();
  }

  Future<void> _reload() async {
    final f = api.fetchFeed();
    setState(() => _future = f);
    await f;
  }

  List<FeedItem> _filter(List<FeedItem> items) {
    return items.where((it) {
      if (_onlyStarred && !readStore.isStarred(it.id)) return false;
      if (_hideRead && !_onlyStarred && readStore.isRead(it.id)) return false;
      return true;
    }).toList();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Nieuwsfeed'),
        actions: [
          IconButton(
            tooltip: 'Herladen',
            icon: const Icon(Icons.refresh),
            onPressed: _reload,
          ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            child: Row(
              children: [
                FilterChip(
                  label: const Text('Bewaard'),
                  avatar: const Icon(Icons.star, size: 16),
                  selected: _onlyStarred,
                  onSelected: (v) => setState(() => _onlyStarred = v),
                ),
                const Spacer(),
                const Text('Verberg gelezen'),
                Switch(
                  value: _hideRead,
                  onChanged: _onlyStarred ? null : (v) => setState(() => _hideRead = v),
                ),
              ],
            ),
          ),
          Expanded(
            child: FutureBuilder<List<FeedItem>>(
              future: _future,
              builder: (context, snap) {
                if (snap.connectionState == ConnectionState.waiting) {
                  return const Center(child: CircularProgressIndicator());
                }
                if (snap.hasError) {
                  return _ErrorView(onRetry: _reload, error: '${snap.error}');
                }
                final all = snap.data ?? const <FeedItem>[];
                return ListenableBuilder(
                  listenable: readStore,
                  builder: (context, _) {
                    final items = _filter(all);
                    return RefreshIndicator(
                      onRefresh: _reload,
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
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  void _open(List<FeedItem> items, int idx) {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => DetailScreen(items: items, initialIndex: idx),
    ));
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
            if (item.isPodcast)
              Padding(
                padding: const EdgeInsets.only(top: 2, right: 6),
                child: Icon(Icons.podcasts, size: 18, color: theme.colorScheme.primary),
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
          Text(it.displayTitle, style: Theme.of(context).textTheme.headlineSmall),
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
