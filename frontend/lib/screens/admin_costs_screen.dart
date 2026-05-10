import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../providers/auth_provider.dart';

/// Domeinmodellen voor het admin-kosten-scherm. Klein genoeg om hier te
/// houden in plaats van models.dart aan te dikken — die wordt door
/// gewone users ook geladen, en cost-objecten zijn admin-only.
class CostTotals {
  final double today;
  final double thisMonth;
  final double thisYear;
  final double all;
  final int callCountAll;
  CostTotals(this.today, this.thisMonth, this.thisYear, this.all, this.callCountAll);
  factory CostTotals.fromJson(Map<String, dynamic> j) => CostTotals(
        (j['today'] ?? 0).toDouble(),
        (j['thisMonth'] ?? 0).toDouble(),
        (j['thisYear'] ?? 0).toDouble(),
        (j['all'] ?? 0).toDouble(),
        (j['callCountAll'] ?? 0) as int,
      );
}

class DailyTotal {
  final String date;
  final double total;
  final Map<String, double> byProvider;
  final int callCount;
  DailyTotal(this.date, this.total, this.byProvider, this.callCount);
  factory DailyTotal.fromJson(Map<String, dynamic> j) => DailyTotal(
        j['date'] as String,
        (j['total'] ?? 0).toDouble(),
        (j['byProvider'] as Map?)?.map((k, v) => MapEntry(k as String, (v as num).toDouble())) ?? {},
        (j['callCount'] ?? 0) as int,
      );
}

class UserTotal {
  final String username;
  final double total;
  final Map<String, double> byProvider;
  final int callCount;
  UserTotal(this.username, this.total, this.byProvider, this.callCount);
  factory UserTotal.fromJson(Map<String, dynamic> j) => UserTotal(
        j['username'] as String,
        (j['total'] ?? 0).toDouble(),
        (j['byProvider'] as Map?)?.map((k, v) => MapEntry(k as String, (v as num).toDouble())) ?? {},
        (j['callCount'] ?? 0) as int,
      );
}

class ExternalCallRow {
  final String id;
  final String provider;
  final String action;
  final String username;
  final String startTime;
  final int durationMs;
  final int? tokensIn;
  final int? tokensOut;
  final int? units;
  final String unitType;
  final double costUsd;
  final String status;
  final String? errorMessage;
  final String? subject;
  ExternalCallRow({
    required this.id,
    required this.provider,
    required this.action,
    required this.username,
    required this.startTime,
    required this.durationMs,
    required this.unitType,
    required this.costUsd,
    required this.status,
    this.tokensIn,
    this.tokensOut,
    this.units,
    this.errorMessage,
    this.subject,
  });
  factory ExternalCallRow.fromJson(Map<String, dynamic> j) => ExternalCallRow(
        id: j['id'] as String,
        provider: j['provider'] as String,
        action: j['action'] as String,
        username: j['username'] as String,
        startTime: j['startTime'] as String,
        durationMs: (j['durationMs'] ?? 0) as int,
        tokensIn: (j['tokensIn'] as num?)?.toInt(),
        tokensOut: (j['tokensOut'] as num?)?.toInt(),
        units: (j['units'] as num?)?.toInt(),
        unitType: j['unitType'] as String? ?? '',
        costUsd: (j['costUsd'] ?? 0).toDouble(),
        status: j['status'] as String? ?? 'ok',
        errorMessage: j['errorMessage'] as String?,
        subject: j['subject'] as String?,
      );
}

// ----- Providers -----

final _costsTotalsProvider = FutureProvider.autoDispose<CostTotals>((ref) async {
  final api = ref.read(apiProvider);
  final j = await api.get('/api/admin/costs/totals') as Map<String, dynamic>;
  return CostTotals.fromJson(j);
});

final _costsDailyProvider = FutureProvider.autoDispose.family<List<DailyTotal>, int>((ref, days) async {
  final api = ref.read(apiProvider);
  final j = await api.get('/api/admin/costs/daily?days=$days') as List<dynamic>;
  return j.map((e) => DailyTotal.fromJson(e as Map<String, dynamic>)).toList();
});

final _costsByUserProvider = FutureProvider.autoDispose.family<List<UserTotal>, String>((ref, period) async {
  final api = ref.read(apiProvider);
  final j = await api.get('/api/admin/costs/by-user?period=$period') as List<dynamic>;
  return j.map((e) => UserTotal.fromJson(e as Map<String, dynamic>)).toList();
});

final _costsCallsProvider = FutureProvider.autoDispose.family<List<ExternalCallRow>, _CallsFilter>((ref, f) async {
  final api = ref.read(apiProvider);
  final params = <String, String>{
    if (f.username != null) 'user': f.username!,
    if (f.provider != null) 'provider': f.provider!,
    if (f.action != null) 'action': f.action!,
    if (f.status != null) 'status': f.status!,
    'limit': f.limit.toString(),
  };
  final qs = params.entries.map((e) => '${e.key}=${Uri.encodeQueryComponent(e.value)}').join('&');
  final path = qs.isEmpty ? '/api/admin/costs/calls' : '/api/admin/costs/calls?$qs';
  final j = await api.get(path) as List<dynamic>;
  return j.map((e) => ExternalCallRow.fromJson(e as Map<String, dynamic>)).toList();
});

class _CallsFilter {
  final String? username;
  final String? provider;
  final String? action;
  final String? status;
  final int limit;
  const _CallsFilter({this.username, this.provider, this.action, this.status, this.limit = 200});

  @override
  bool operator ==(Object other) =>
      other is _CallsFilter &&
      other.username == username &&
      other.provider == provider &&
      other.action == action &&
      other.status == status &&
      other.limit == limit;

  @override
  int get hashCode => Object.hash(username, provider, action, status, limit);
}

// ----- Screen -----

class AdminCostsScreen extends ConsumerStatefulWidget {
  const AdminCostsScreen({super.key});

  @override
  ConsumerState<AdminCostsScreen> createState() => _AdminCostsScreenState();
}

class _AdminCostsScreenState extends ConsumerState<AdminCostsScreen> {
  String _userPeriod = 'this_month';
  _CallsFilter _callsFilter = const _CallsFilter();

  @override
  Widget build(BuildContext context) {
    final totals = ref.watch(_costsTotalsProvider);
    return DefaultTabController(
      length: 3,
      child: Scaffold(
        appBar: AppBar(
          // Geen leading meegeven: Flutter zet automatisch een back-pijl
          // omdat dit scherm via Navigator.push() is geopend. Een AppLogo
          // op deze plek zou de back-knop overschrijven.
          title: const Text('Kosten'),
          actions: [
            IconButton(
              tooltip: 'Vernieuwen',
              icon: const Icon(Icons.refresh),
              onPressed: () {
                ref.invalidate(_costsTotalsProvider);
                ref.invalidate(_costsDailyProvider);
                ref.invalidate(_costsByUserProvider);
                ref.invalidate(_costsCallsProvider);
              },
            ),
          ],
          bottom: const TabBar(
            tabs: [
              Tab(text: 'Per dag'),
              Tab(text: 'Per gebruiker'),
              Tab(text: 'Logboek'),
            ],
          ),
        ),
        body: Column(
          children: [
            _TotalsHeader(totals),
            const Divider(height: 1),
            Expanded(
              child: TabBarView(children: [
                _DailyTab(),
                _ByUserTab(
                  period: _userPeriod,
                  onPeriodChanged: (p) => setState(() => _userPeriod = p),
                ),
                _CallsTab(
                  filter: _callsFilter,
                  onFilterChanged: (f) => setState(() => _callsFilter = f),
                ),
              ]),
            ),
          ],
        ),
      ),
    );
  }
}

// ----- Header cards -----

class _TotalsHeader extends ConsumerWidget {
  final AsyncValue<CostTotals> totals;
  const _TotalsHeader(this.totals);

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return SizedBox(
      height: 100,
      child: totals.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Fout: $e')),
        data: (t) => Padding(
          padding: const EdgeInsets.fromLTRB(12, 12, 12, 8),
          child: Row(children: [
            Expanded(child: _Card('Vandaag', t.today)),
            const SizedBox(width: 8),
            Expanded(child: _Card('Deze maand', t.thisMonth)),
            const SizedBox(width: 8),
            Expanded(child: _Card('Dit jaar', t.thisYear)),
            const SizedBox(width: 8),
            Expanded(child: _Card('Totaal', t.all, sub: '${t.callCountAll} calls')),
          ]),
        ),
      ),
    );
  }
}

class _Card extends StatelessWidget {
  final String label;
  final double amount;
  final String? sub;
  const _Card(this.label, this.amount, {this.sub});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(8),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: theme.textTheme.labelMedium),
            const SizedBox(height: 4),
            Text(
              _fmtUsd(amount),
              style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w600),
              overflow: TextOverflow.ellipsis,
            ),
            if (sub != null) Text(sub!, style: theme.textTheme.bodySmall),
          ],
        ),
      ),
    );
  }
}

// ----- Tab 1: Per dag -----

class _DailyTab extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncDaily = ref.watch(_costsDailyProvider(30));
    return asyncDaily.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Center(child: Text('Fout: $e')),
      data: (days) {
        if (days.every((d) => d.callCount == 0)) {
          return const Center(child: Text('Nog geen externe calls geregistreerd'));
        }
        return ListView(
          children: [
            DataTable(
              columnSpacing: 12,
              columns: const [
                DataColumn(label: Text('Datum')),
                DataColumn(label: Text('Totaal'), numeric: true),
                DataColumn(label: Text('Anthropic'), numeric: true),
                DataColumn(label: Text('OpenAI'), numeric: true),
                DataColumn(label: Text('ElevenLabs'), numeric: true),
                DataColumn(label: Text('Tavily'), numeric: true),
                DataColumn(label: Text('Calls'), numeric: true),
              ],
              rows: days.map((d) => DataRow(cells: [
                    DataCell(Text(d.date)),
                    DataCell(Text(_fmtUsd(d.total),
                        style: const TextStyle(fontWeight: FontWeight.w600))),
                    DataCell(Text(_fmtUsdOrDash(d.byProvider['anthropic']))),
                    DataCell(Text(_fmtUsdOrDash(d.byProvider['openai']))),
                    DataCell(Text(_fmtUsdOrDash(d.byProvider['elevenlabs']))),
                    DataCell(Text(_fmtUsdOrDash(d.byProvider['tavily']))),
                    DataCell(Text('${d.callCount}')),
                  ])).toList(),
            ),
          ],
        );
      },
    );
  }
}

// ----- Tab 2: Per gebruiker -----

class _ByUserTab extends ConsumerWidget {
  final String period;
  final ValueChanged<String> onPeriodChanged;
  const _ByUserTab({required this.period, required this.onPeriodChanged});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncUsers = ref.watch(_costsByUserProvider(period));
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
          child: Wrap(spacing: 8, children: [
            for (final p in const [
              ('this_month', 'Deze maand'),
              ('last_month', 'Vorige maand'),
              ('this_year', 'Dit jaar'),
              ('all', 'Alles'),
            ])
              ChoiceChip(
                label: Text(p.$2),
                selected: period == p.$1,
                onSelected: (_) => onPeriodChanged(p.$1),
              ),
          ]),
        ),
        Expanded(
          child: asyncUsers.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(child: Text('Fout: $e')),
            data: (users) {
              if (users.isEmpty || users.every((u) => u.callCount == 0)) {
                return const Center(child: Text('Geen calls in deze periode'));
              }
              return ListView(
                children: [
                  DataTable(
                    columnSpacing: 12,
                    columns: const [
                      DataColumn(label: Text('Gebruiker')),
                      DataColumn(label: Text('Totaal'), numeric: true),
                      DataColumn(label: Text('Anthropic'), numeric: true),
                      DataColumn(label: Text('OpenAI'), numeric: true),
                      DataColumn(label: Text('ElevenLabs'), numeric: true),
                      DataColumn(label: Text('Tavily'), numeric: true),
                      DataColumn(label: Text('Calls'), numeric: true),
                    ],
                    rows: users.map((u) => DataRow(cells: [
                          DataCell(Text(u.username)),
                          DataCell(Text(_fmtUsd(u.total),
                              style: const TextStyle(fontWeight: FontWeight.w600))),
                          DataCell(Text(_fmtUsdOrDash(u.byProvider['anthropic']))),
                          DataCell(Text(_fmtUsdOrDash(u.byProvider['openai']))),
                          DataCell(Text(_fmtUsdOrDash(u.byProvider['elevenlabs']))),
                          DataCell(Text(_fmtUsdOrDash(u.byProvider['tavily']))),
                          DataCell(Text('${u.callCount}')),
                        ])).toList(),
                  ),
                ],
              );
            },
          ),
        ),
      ],
    );
  }
}

// ----- Tab 3: Logboek -----

class _CallsTab extends ConsumerWidget {
  final _CallsFilter filter;
  final ValueChanged<_CallsFilter> onFilterChanged;
  const _CallsTab({required this.filter, required this.onFilterChanged});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncCalls = ref.watch(_costsCallsProvider(filter));
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
          child: Wrap(spacing: 6, runSpacing: 4, children: [
            _filterChip(
              label: filter.provider == null ? 'Alle providers' : 'Provider: ${filter.provider}',
              selected: filter.provider != null,
              onTap: () => _pickFilter(
                context,
                'Provider',
                ['(alle)', 'anthropic', 'openai', 'elevenlabs', 'tavily', 'rss', 'web'],
                (v) => onFilterChanged(_copy(provider: v)),
              ),
            ),
            _filterChip(
              label: filter.status == null ? 'Alle statussen' : 'Status: ${filter.status}',
              selected: filter.status != null,
              onTap: () => _pickFilter(
                context,
                'Status',
                ['(alle)', 'ok', 'error'],
                (v) => onFilterChanged(_copy(status: v)),
              ),
            ),
            _filterChip(
              label: filter.action == null ? 'Alle acties' : 'Actie: ${filter.action}',
              selected: filter.action != null,
              onTap: () => _pickFilter(
                context,
                'Actie',
                const [
                  '(alle)',
                  'rss_fetch',
                  'rss_summarize',
                  'feed_summarize',
                  'feed_score',
                  'daily_summary',
                  'article_fetch',
                  'podcast_topics',
                  'podcast_script',
                  'podcast_tts',
                  'tavily_search',
                  'tavily_extract',
                  'adhoc_summarize',
                ],
                (v) => onFilterChanged(_copy(action: v)),
              ),
            ),
          ]),
        ),
        Expanded(
          child: asyncCalls.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(child: Text('Fout: $e')),
            data: (rows) {
              if (rows.isEmpty) return const Center(child: Text('Geen calls'));
              return ListView.separated(
                itemCount: rows.length,
                separatorBuilder: (_, __) => const Divider(height: 1),
                itemBuilder: (ctx, i) {
                  final r = rows[i];
                  final isError = r.status == 'error';
                  return ListTile(
                    dense: true,
                    leading: CircleAvatar(
                      backgroundColor: isError
                          ? Colors.red.shade100
                          : Theme.of(context).colorScheme.surfaceContainerHighest,
                      foregroundColor: isError ? Colors.red : null,
                      child: Text(_providerInitial(r.provider),
                          style: const TextStyle(fontWeight: FontWeight.w600)),
                    ),
                    title: Text('${r.action} · ${r.username}'),
                    subtitle: Text(
                      '${r.startTime.replaceFirst('T', ' ').substring(0, 19)}'
                      ' · ${r.durationMs}ms'
                      '${r.units != null ? ' · ${r.units} ${r.unitType}' : ''}'
                      '${r.subject != null ? '\n${r.subject}' : ''}'
                      '${r.errorMessage != null ? '\nfout: ${r.errorMessage}' : ''}',
                    ),
                    isThreeLine: r.subject != null || r.errorMessage != null,
                    trailing: Text(
                      _fmtUsd(r.costUsd),
                      style: TextStyle(
                        fontWeight: FontWeight.w600,
                        color: isError ? Colors.red : null,
                      ),
                    ),
                  );
                },
              );
            },
          ),
        ),
      ],
    );
  }

  Widget _filterChip({required String label, required bool selected, required VoidCallback onTap}) {
    return ActionChip(
      label: Text(label),
      onPressed: onTap,
      backgroundColor: selected ? null : null,
    );
  }

  Future<void> _pickFilter(
    BuildContext context,
    String title,
    List<String> options,
    ValueChanged<String?> onPicked,
  ) async {
    final picked = await showDialog<String>(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: Text(title),
        children: options
            .map((o) => SimpleDialogOption(
                  onPressed: () => Navigator.pop(ctx, o),
                  child: Text(o),
                ))
            .toList(),
      ),
    );
    if (picked == null) return;
    onPicked(picked == '(alle)' ? null : picked);
  }

  _CallsFilter _copy({Object? provider = _sentinel, Object? status = _sentinel, Object? action = _sentinel}) {
    return _CallsFilter(
      username: filter.username,
      provider: provider == _sentinel ? filter.provider : provider as String?,
      status: status == _sentinel ? filter.status : status as String?,
      action: action == _sentinel ? filter.action : action as String?,
      limit: filter.limit,
    );
  }

  static const _sentinel = Object();

  String _providerInitial(String provider) => switch (provider) {
        'anthropic' => 'A',
        'openai' => 'O',
        'elevenlabs' => 'E',
        'tavily' => 'T',
        'rss' => 'R',
        'web' => 'W',
        _ => '?'
      };
}

// ----- Helpers -----

String _fmtUsd(double v) {
  if (v == 0) return '\$0.00';
  // Voor zeer kleine bedragen 4 decimalen, anders 2.
  if (v.abs() < 0.10) return '\$${v.toStringAsFixed(4)}';
  return '\$${v.toStringAsFixed(2)}';
}

String _fmtUsdOrDash(double? v) {
  if (v == null || v == 0) return '—';
  return _fmtUsd(v);
}
