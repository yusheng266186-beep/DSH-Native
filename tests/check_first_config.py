#!/usr/bin/env python3
"""Exercise production first-configuration file I/O without Android UI."""
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'src/dev/dsh/nativeapp'
if not SOURCE.is_dir():
    SOURCE = ROOT / 'bootstrap/src/dev/dsh/nativeapp'
text = (SOURCE / 'MainActivity.java').read_text()


def method(signature):
    start = text.index(signature)
    end = text.index('{', start) + 1
    depth = 1
    while depth:
        if text[end] == '{':
            depth += 1
        elif text[end] == '}':
            depth -= 1
        end += 1
    return text[start:end]


methods = '\n'.join(method(s) for s in [
    'private void prepareConfig(', 'private String readText(',
    'private void writeText(', 'private java.util.List<String> mergeTopLevelBlocks(',
])
harness = r'''
package dev.dsh.nativeapp;
import java.io.*;import java.nio.file.*;import java.util.*;
public class FirstConfigProbe {
 void log(String message){}
 String activeProjectName(){return "default";}
 void applyProjectModelConfig(String name,File settings){}
 void syncProviderConfig(File root,File settings){}
 List<String> mergeCredentials(File source,File target){throw new AssertionError("unexpected shared credential");}
 __METHODS__
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception {
  File root=new File(args[0]),home=new File(root,".dsh");
  String preset="agent-default-model:\n  provider: codebuddy\nllm-pi-ai:\n  providers:\n    codebuddy:\n      models:\n        - id: deepseek-v4.1-flash\n";
  Files.writeString(new File(root,"settings-preset.yaml").toPath(),preset);
  FirstConfigProbe p=new FirstConfigProbe();p.prepareConfig(root);
  File settings=new File(home,"settings.yaml"),imported=new File(home,"settings.yaml.imported");
  check(settings.isFile()&&ModelCatalogPersistence.hasModelCatalog(Files.readString(settings.toPath()))
   &&Files.readString(settings.toPath()).contains("agent-default-model:\n  provider: codebuddy\n"),"clean installation creates the preset without an imported file");
  String custom="agent-default-model:\n  provider: custom\n";
  Files.writeString(settings.toPath(),custom);p.prepareConfig(root);
  check(Files.readString(settings.toPath()).startsWith(custom),"pending configuration keeps the user's default");
  Files.move(settings.toPath(),imported.toPath());
  String retained=Files.readString(imported.toPath());p.prepareConfig(root);
  check(!settings.exists()&&Files.readString(imported.toPath()).equals(retained),"an already imported catalog is not recreated or overwritten");
  Files.delete(imported.toPath());p.prepareConfig(root);
  check(settings.isFile(),"missing import history can initialize again");
  System.out.println("First configuration: 4 production file-I/O checks passed.");
 }
}
'''.replace('__METHODS__', methods)
with tempfile.TemporaryDirectory(prefix='dsh-first-config-') as tmp:
    base = pathlib.Path(tmp)
    java = base / 'FirstConfigProbe.java'
    java.write_text(harness)
    classes = base / 'classes'
    classes.mkdir()
    subprocess.run(['javac', '-d', str(classes), str(java),
                    str(SOURCE / 'ModelCatalogPersistence.java'),
                    str(SOURCE / 'YamlBlocks.java')], check=True)
    files = base / 'files'
    files.mkdir()
    subprocess.run(['java', '-cp', str(classes), 'dev.dsh.nativeapp.FirstConfigProbe',
                    str(files)], check=True)
