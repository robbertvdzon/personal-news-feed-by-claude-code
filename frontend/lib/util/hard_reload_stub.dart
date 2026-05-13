/// Stub voor non-web platforms. De hard-reload-flow is alleen op web
/// nodig (zie `hard_reload_web.dart`); op iOS/Android updaten gebruikers
/// via de store of nieuwe APK.
Future<void> hardReload() async {
  // no-op
}
