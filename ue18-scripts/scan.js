var fso = new ActiveXObject("Scripting.FileSystemObject");
var shell = new ActiveXObject("WScript.Shell");
var configPath = UltraEdit.activeDocument.path + "wlh.config.json";
if (!fso.FileExists(configPath)) {
  UltraEdit.outputWindow.write("Missing wlh.config.json in current directory\n");
} else {
  var file = fso.OpenTextFile(configPath, 1).ReadAll();
  var config = eval('(' + file + ')');
  var wlhPath = config.wlhPath;
  if (!wlhPath) {
    UltraEdit.outputWindow.write("Missing wlhPath in wlh.config.json\n");
  } else {
    var filePath = UltraEdit.activeDocument.path + UltraEdit.activeDocument.name;
    var cmd = "\"" + wlhPath + "\" scan \"" + filePath + "\"";
    var exec = shell.Exec(cmd);
    var output = exec.StdOut.ReadAll();
    UltraEdit.outputWindow.write(output);
    var resultPath = filePath + ".wlhresult";
    if (!fso.FileExists(resultPath)) {
      UltraEdit.outputWindow.write("Missing .wlhresult file: " + resultPath + "\n");
      return;
    }
    var resultRaw = fso.OpenTextFile(resultPath, 1).ReadAll();
    var result = eval('(' + resultRaw + ')');
    var lines = [];
    lines.push("WLH Scan View");
    lines.push("Source: " + filePath);
    lines.push("Generated: " + (result.generatedAt || ""));
    lines.push("");
    lines.push("Versions:");
    if (result.versions && result.versions.length > 0) {
      for (var i = 0; i < result.versions.length; i++) {
        var entry = result.versions[i];
        if (typeof entry === "string") {
          lines.push("  " + entry);
        } else if (entry.label) {
          if (entry.line) {
            lines.push("  L" + entry.line + ": " + entry.label);
          } else {
            lines.push("  " + entry.label);
          }
        }
      }
    } else {
      lines.push("  (none)");
    }
    lines.push("");
    lines.push("Crashes:");
    if (result.crashes && result.crashes.length > 0) {
      for (var c = 0; c < result.crashes.length; c++) {
        var crash = result.crashes[c];
        var preview = crash.preview || "";
        var previewLines = preview.split(/\r?\n/);
        if (previewLines.length > 0) {
          lines.push("  L" + crash.line + ": " + previewLines[0]);
          for (var p = 1; p < previewLines.length; p++) {
            lines.push("        " + previewLines[p]);
          }
        } else {
          lines.push("  L" + crash.line + ":");
        }
      }
    } else {
      lines.push("  (none)");
    }
    lines.push("");
    lines.push("Tip: select a line like L123 and run jump.js to jump.");
    var viewPath = filePath + ".wlhview.txt";
    var viewFile = fso.CreateTextFile(viewPath, true);
    viewFile.Write(lines.join("\r\n"));
    viewFile.Close();
    UltraEdit.open(viewPath);
  }
}
