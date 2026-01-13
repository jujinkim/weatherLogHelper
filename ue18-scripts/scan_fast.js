var fso = new ActiveXObject("Scripting.FileSystemObject");
var shell = new ActiveXObject("WScript.Shell");
var filePath = UltraEdit.activeDocument.path + UltraEdit.activeDocument.name;
var cmd = "wlh scan --mode fast \"" + filePath + "\"";
var exec = shell.Exec(cmd);
var output = exec.StdOut.ReadAll();
UltraEdit.outputWindow.write(output);
