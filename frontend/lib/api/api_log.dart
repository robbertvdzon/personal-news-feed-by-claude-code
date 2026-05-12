import 'package:flutter/foundation.dart';

/// In-memory ringbuffer met de laatste HTTP-calls die [ApiClient] heeft
/// gedaan. Bedoeld voor debugging vanaf de telefoon — zichtbaar via
/// Settings → API-log.
///
/// Niet persistent: bij app-restart leeg.
class ApiLogEntry {
  final DateTime time;
  final String method;
  final String url;

  /// HTTP status (null = exception vóór dat een response binnenkwam).
  final int? statusCode;

  /// Response-body bij fout, of exception-message als er geen response was.
  final String? error;
  final int durationMs;

  ApiLogEntry({
    required this.time,
    required this.method,
    required this.url,
    this.statusCode,
    this.error,
    required this.durationMs,
  });

  bool get isError => statusCode == null || statusCode! >= 400;

  String get summary {
    final hh = time.hour.toString().padLeft(2, '0');
    final mm = time.minute.toString().padLeft(2, '0');
    final ss = time.second.toString().padLeft(2, '0');
    final status = statusCode?.toString() ?? 'ERR';
    return '$hh:$mm:$ss  $status  $method  $url  (${durationMs}ms)';
  }
}

class ApiLog {
  static const int maxEntries = 50;
  static final ApiLog instance = ApiLog._();
  ApiLog._();

  final List<ApiLogEntry> _entries = [];
  final ValueNotifier<int> revision = ValueNotifier(0);

  List<ApiLogEntry> get entries => List.unmodifiable(_entries);

  void add(ApiLogEntry e) {
    _entries.insert(0, e);
    if (_entries.length > maxEntries) {
      _entries.removeRange(maxEntries, _entries.length);
    }
    revision.value++;
  }

  void clear() {
    _entries.clear();
    revision.value++;
  }
}
