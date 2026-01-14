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
  }
}
