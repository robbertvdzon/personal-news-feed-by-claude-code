import 'package:flutter/widgets.dart';
import 'package:google_sign_in_web/web_only.dart' as web_only;

/// Op web vereist Google Identity Services de officiële gerenderde knop.
Widget renderGoogleButton() => web_only.renderButton();
