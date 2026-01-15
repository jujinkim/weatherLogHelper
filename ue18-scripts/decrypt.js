var fso = new ActiveXObject("Scripting.FileSystemObject");
var shell = new ActiveXObject("WScript.Shell");

function parseJson(text, label) {
  try {
    return eval("(" + text + ")");
  } catch (err) {
    UltraEdit.outputWindow.write("Failed to parse " + label + " JSON\n");
    return null;
  }
}

var configPath = UltraEdit.activeDocument.path + "wlh.config.json";
if (!fso.FileExists(configPath)) {
  UltraEdit.outputWindow.write("Missing wlh.config.json in current directory\n");
} else {
  var file = fso.OpenTextFile(configPath, 1).ReadAll();
  var config = parseJson(file, "config");
  if (!config) {
    return;
  }
  var jarPath = config.decryptJar;
  var wlhPath = config.wlhPath;
  if (!wlhPath) {
    UltraEdit.outputWindow.write("Missing wlhPath in wlh.config.json\n");
  } else if (!jarPath) {
    UltraEdit.outputWindow.write("Missing decryptJar in wlh.config.json\n");
  } else {
    var filePath = UltraEdit.activeDocument.path + UltraEdit.activeDocument.name;
    var cmd = "\"" + wlhPath + "\" decrypt \"" + filePath + "\" --jar \"" + jarPath + "\"";
    var exec = shell.Exec(cmd);
    var output = exec.StdOut.ReadAll();
    UltraEdit.outputWindow.write(output);
    try {
      var result = parseJson(output, "decrypt result");
      if (!result) {
        return;
      }
      if (result.status === "ok" && result.output) {
        UltraEdit.open(result.output);
      } else if (result.status) {
        UltraEdit.outputWindow.write("Decrypt failed: " + result.status + "\n");
      }
    } catch (err) {
      UltraEdit.outputWindow.write("Failed to parse decrypt output\n");
    }
  }
}
