import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'dashboard_tab.dart';
import 'stories_tab.dart';
import 'releases_tab.dart';
import 'downloads_tab.dart';
import 'settings_tab.dart';

/// Hoofdscaffold met sidebar (desktop) of hamburger-drawer (mobiel).
/// Vijf tabs: Dashboard, Stories, Recent gemerged, Downloads, Settings.
class AppShell extends ConsumerStatefulWidget {
  const AppShell({super.key});
  @override
  ConsumerState<AppShell> createState() => _AppShellState();
}

class _NavItem {
  final String label;
  final IconData icon;
  final IconData iconSelected;
  final Widget body;
  const _NavItem(this.label, this.icon, this.iconSelected, this.body);
}

class _AppShellState extends ConsumerState<AppShell> {
  int _index = 0;

  static final List<_NavItem> _items = [
    _NavItem('Dashboard', Icons.dashboard_outlined, Icons.dashboard,
        const DashboardTab()),
    _NavItem('Stories', Icons.list_alt_outlined, Icons.list_alt,
        const StoriesTab()),
    _NavItem('Recent gemerged', Icons.history_outlined, Icons.history,
        const ReleasesTab()),
    _NavItem('Downloads', Icons.download_outlined, Icons.download,
        const DownloadsTab()),
    _NavItem('Settings', Icons.settings_outlined, Icons.settings,
        const SettingsTab()),
  ];

  @override
  Widget build(BuildContext context) {
    // Phones (shortestSide < 600 = Material's phone-vs-tablet grens)
    // krijgen ALTIJD het hamburger-menu, ook in landscape — anders past
    // de sidebar naast de content niet meer comfortabel. Tablets/desktop
    // zien de sidebar zodra de width breed genoeg is.
    final phone = MediaQuery.sizeOf(context).shortestSide < 600;
    return LayoutBuilder(
      builder: (context, constraints) {
        final wide = !phone && constraints.maxWidth >= 720;
        return wide ? _wideLayout(context) : _narrowLayout(context);
      },
    );
  }

  Widget _wideLayout(BuildContext context) {
    return Scaffold(
      body: Row(
        children: [
          _Sidebar(
            items: _items,
            selected: _index,
            onSelect: (i) => setState(() => _index = i),
          ),
          Expanded(child: _items[_index].body),
        ],
      ),
    );
  }

  Widget _narrowLayout(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        leading: Builder(
          builder: (ctx) => IconButton(
            icon: const Icon(Icons.menu),
            onPressed: () => Scaffold.of(ctx).openDrawer(),
          ),
        ),
        titleSpacing: 0,
        title: Row(
          children: [
            _AppLogo(),
            const SizedBox(width: 10),
            Text(_items[_index].label,
                style: TextStyle(
                  color: scheme.onSurface,
                  fontSize: 18,
                  fontWeight: FontWeight.w600,
                )),
          ],
        ),
      ),
      drawer: Drawer(
        backgroundColor: Colors.white,
        child: SafeArea(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const _BrandHeader(),
              const SizedBox(height: 8),
              for (int i = 0; i < _items.length; i++)
                _NavTile(
                  item: _items[i],
                  selected: i == _index,
                  onTap: () {
                    setState(() => _index = i);
                    Navigator.of(context).pop();
                  },
                ),
            ],
          ),
        ),
      ),
      body: _items[_index].body,
    );
  }
}

class _Sidebar extends StatelessWidget {
  final List<_NavItem> items;
  final int selected;
  final ValueChanged<int> onSelect;
  const _Sidebar({required this.items, required this.selected, required this.onSelect});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: 220,
      decoration: BoxDecoration(
        color: Colors.white,
        border: Border(right: BorderSide(color: scheme.outlineVariant, width: 1)),
      ),
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const _BrandHeader(),
            const SizedBox(height: 12),
            for (int i = 0; i < items.length; i++)
              _NavTile(
                item: items[i],
                selected: i == selected,
                onTap: () => onSelect(i),
              ),
            const Spacer(),
            const SizedBox(height: 12),
          ],
        ),
      ),
    );
  }
}

class _BrandHeader extends StatelessWidget {
  const _BrandHeader();

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 18, 16, 8),
      child: Row(
        children: [
          _AppLogo(),
          const SizedBox(width: 10),
          Text('Software Factory',
              style: TextStyle(
                  color: scheme.onSurface,
                  fontSize: 15,
                  fontWeight: FontWeight.w700)),
        ],
      ),
    );
  }
}

class _AppLogo extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: 32,
      height: 32,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: scheme.primaryContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Icon(Icons.precision_manufacturing,
          size: 18, color: scheme.onPrimaryContainer),
    );
  }
}

class _NavTile extends StatelessWidget {
  final _NavItem item;
  final bool selected;
  final VoidCallback onTap;
  const _NavTile({required this.item, required this.selected, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      child: Material(
        color: selected ? scheme.secondaryContainer : Colors.transparent,
        borderRadius: BorderRadius.circular(10),
        child: InkWell(
          borderRadius: BorderRadius.circular(10),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            child: Row(
              children: [
                Icon(
                  selected ? item.iconSelected : item.icon,
                  size: 18,
                  color: selected ? scheme.onSecondaryContainer : scheme.onSurfaceVariant,
                ),
                const SizedBox(width: 12),
                Text(item.label,
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                      color: selected ? scheme.onSecondaryContainer : scheme.onSurface,
                    )),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
