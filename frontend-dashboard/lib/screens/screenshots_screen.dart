import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../api/api_client.dart';
import '../api/models.dart';
import '../providers/auth_provider.dart';
import '../providers/data_providers.dart';

/// Per-story grid van tester-screenshots (JIRA-attachments). Tap op een
/// thumbnail opent de fullscreen viewer met pinch-to-zoom.
class ScreenshotsScreen extends ConsumerWidget {
  final String storyKey;
  const ScreenshotsScreen({super.key, required this.storyKey});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncShots = ref.watch(screenshotsProvider(storyKey));
    final api = ref.read(apiProvider);
    final headers = api.authHeaders();
    return Scaffold(
      appBar: AppBar(
        title: Text('Screenshots — $storyKey'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () => ref.invalidate(screenshotsProvider(storyKey)),
          ),
        ],
      ),
      body: asyncShots.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Fout: $e')),
        data: (shots) {
          if (shots.isEmpty) {
            return const Center(
              child: Padding(
                padding: EdgeInsets.all(24),
                child: Text('Geen screenshots gevonden voor deze story.'),
              ),
            );
          }
          return GridView.builder(
            padding: const EdgeInsets.all(12),
            gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
              maxCrossAxisExtent: 320,
              crossAxisSpacing: 12,
              mainAxisSpacing: 12,
              childAspectRatio: 0.85,
            ),
            itemCount: shots.length,
            itemBuilder: (ctx, i) => _Tile(
              shot: shots[i],
              api: api,
              headers: headers,
              onOpen: () => _openViewer(context, shots, i, api, headers),
            ),
          );
        },
      ),
    );
  }

  void _openViewer(BuildContext context, List<ScreenshotAttachment> shots,
      int index, ApiClient api, Map<String, String> headers) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => _Viewer(
            shots: shots, initialIndex: index, api: api, headers: headers),
      ),
    );
  }
}

class _Tile extends StatelessWidget {
  final ScreenshotAttachment shot;
  final ApiClient api;
  final Map<String, String> headers;
  final VoidCallback onOpen;
  const _Tile({
    required this.shot,
    required this.api,
    required this.headers,
    required this.onOpen,
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      clipBehavior: Clip.hardEdge,
      child: InkWell(
        onTap: onOpen,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: ColoredBox(
                color: scheme.surfaceContainerHighest,
                child: Image.network(
                  api.attachmentRawUrl(shot.rawUrl),
                  headers: headers,
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) => Center(
                    child: Icon(Icons.broken_image_outlined,
                        color: scheme.onSurfaceVariant, size: 36),
                  ),
                  loadingBuilder: (_, child, p) => p == null
                      ? child
                      : const Center(child: CircularProgressIndicator()),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(10, 8, 10, 10),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(shot.filename,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontSize: 13, fontWeight: FontWeight.w600)),
                  Text(
                    '${(shot.size / 1024).toStringAsFixed(0)} KB',
                    style: TextStyle(
                        fontSize: 11, color: scheme.onSurfaceVariant),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Viewer extends StatefulWidget {
  final List<ScreenshotAttachment> shots;
  final int initialIndex;
  final ApiClient api;
  final Map<String, String> headers;
  const _Viewer({
    required this.shots,
    required this.initialIndex,
    required this.api,
    required this.headers,
  });
  @override
  State<_Viewer> createState() => _ViewerState();
}

class _ViewerState extends State<_Viewer> {
  late final PageController _ctrl =
      PageController(initialPage: widget.initialIndex);
  late int _index = widget.initialIndex;

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final shot = widget.shots[_index];
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        title: Text('${_index + 1} / ${widget.shots.length} — ${shot.filename}',
            style: const TextStyle(fontSize: 14)),
      ),
      body: PageView.builder(
        controller: _ctrl,
        itemCount: widget.shots.length,
        onPageChanged: (i) => setState(() => _index = i),
        itemBuilder: (_, i) => InteractiveViewer(
          minScale: 0.6,
          maxScale: 6,
          child: Center(
            child: Image.network(
              widget.api.attachmentRawUrl(widget.shots[i].rawUrl),
              headers: widget.headers,
              fit: BoxFit.contain,
              errorBuilder: (_, __, ___) => const Icon(
                  Icons.broken_image_outlined,
                  color: Colors.white54,
                  size: 80),
              loadingBuilder: (_, child, p) => p == null
                  ? child
                  : const Center(child: CircularProgressIndicator()),
            ),
          ),
        ),
      ),
    );
  }
}
