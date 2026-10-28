#!/usr/bin/env python3
"""Verify a rest pose against the original vector rather than a redrawn approximation."""
import pathlib,re,shutil,subprocess,tempfile,xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parents[1]
source=root/'src/dev/dsh/nativeapp'
if not source.is_dir():source=root/'bootstrap/src/dev/dsh/nativeapp'
vector=next(p for p in [root/'icon/res/drawable/dsh_startup_whale.xml',root/'bootstrap/res/drawable/dsh_startup_whale.xml'] if p.is_file())
canonical=ET.parse(vector).getroot().find('path').get('{http://schemas.android.com/apk/res/android}pathData')
with tempfile.TemporaryDirectory(prefix='whale-shape-') as temp:
    compiler=[shutil.which('javac')] if shutil.which('javac') else ['java','-m','jdk.compiler/com.sun.tools.javac.Main']
    subprocess.run([*compiler,'-encoding','UTF-8','-d',temp,str(source/'WhaleShape.java'),str(root/'tests/WhaleShapeTest.java')],check=True)
    emitted=subprocess.check_output(['java','-cp',temp,'dev.dsh.nativeapp.WhaleShapeTest','--dump']).decode()
    tokenize=lambda s:re.findall(r'[MCZ]|[-+]?(?:\d*\.\d+|\d+)',s)
    a,b=tokenize(canonical),tokenize(emitted)
    assert len(a)==len(b)
    assert all(x==y if x in 'MCZ' else abs(float(x)-float(y))<.00001 for x,y in zip(a,b))
print('Whale rest pose: all original commands, contour coordinates and cutouts preserved.')
