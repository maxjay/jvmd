#!/usr/bin/env python3
"""Deterministic sources and independent expectations for prepared LSP requests."""
import hashlib
import json
import subprocess
from pathlib import Path
from xml.sax.saxutils import escape


def position(text, offset):
    return {"line": text.count("\n", 0, offset), "character": offset - text.rfind("\n", 0, offset) - 1}


def location(file, text, token, offset=0):
    start = text.index(token, offset)
    return {
        "uri": file.as_uri(),
        "range": {"start": position(text, start), "end": position(text, start + len(token))},
    }


def create(root, java_home, targets=3, sources=24):
    root.mkdir(parents=True)
    repository = root / "repository"
    jar_dir = repository / "external/offset/1"
    jar_dir.mkdir(parents=True)
    external = root / "external/external/Offset.java"
    external.parent.mkdir(parents=True)
    external.write_text(
        "package external;\npublic class Offset {\n"
        + "".join(
            f"  public static int base{i}(int input) {{ return input + {40+i}; }}\n" for i in range(targets)
        )
        + "}\n"
    )
    classes = root / "external-classes"
    subprocess.run(
        [str(java_home / "bin/javac"), "--release", "17", "-g", "-d", str(classes), str(external)], check=True
    )
    for suffix, directory in [("", classes), ("-sources", external.parent.parent)]:
        subprocess.run(
            [
                str(java_home / "bin/jar"),
                "--create",
                "--date=2025-01-01T00:00:00Z",
                "--file",
                str(jar_dir / f"offset-1{suffix}.jar"),
                "-C",
                str(directory),
                ".",
            ],
            check=True,
        )
    (jar_dir / "offset-1.pom").write_text(
        "<project><modelVersion>4.0.0</modelVersion><groupId>external</groupId><artifactId>offset</artifactId><version>1</version></project>"
    )
    host, library = root / "host", root / "library"
    binary, attachment = jar_dir / "offset-1.jar", jar_dir / "offset-1-sources.jar"
    for project in (host, library):
        (project / "src/main/java/bench").mkdir(parents=True)
        dep = (
            "<dependency><groupId>bench</groupId><artifactId>library</artifactId><version>1</version></dependency>"
            if project == host
            else "<dependency><groupId>external</groupId><artifactId>offset</artifactId><version>1</version></dependency>"
        )
        (project / "pom.xml").write_text(
            f"<project><modelVersion>4.0.0</modelVersion><groupId>bench</groupId><artifactId>{project.name}</artifactId><version>1</version><properties><maven.compiler.release>17</maven.compiler.release></properties><dependencies>{dep}</dependencies></project>"
        )
        (project / ".project").write_text(
            f"<projectDescription><name>{project.name}</name><buildSpec><buildCommand><name>org.eclipse.jdt.core.javabuilder</name><arguments/></buildCommand></buildSpec><natures><nature>org.eclipse.jdt.core.javanature</nature></natures></projectDescription>"
        )
        dependencies = (
            '<classpathentry kind="src" path="/library"/>'
            if project == host
            else f'<classpathentry kind="lib" exported="true" path="{escape(str(binary))}" sourcepath="{escape(str(attachment))}"/>'
        )
        (project / ".classpath").write_text(
            '<classpath><classpathentry kind="src" path="src/main/java"/>'
            + dependencies
            + '<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/><classpathentry kind="output" path="bin"/></classpath>'
        )
        wrapper = project / ".mvn/wrapper"
        wrapper.mkdir(parents=True)
        (wrapper / "maven-wrapper.properties").write_text(
            "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip\n"
        )
        settings = project / ".settings"
        settings.mkdir()
        (settings / "org.eclipse.jdt.core.prefs").write_text(
            "eclipse.preferences.version=1\norg.eclipse.jdt.core.compiler.compliance=17\norg.eclipse.jdt.core.compiler.source=17\norg.eclipse.jdt.core.compiler.codegen.targetPlatform=17\n"
        )
    manifest = host / ".jvmd/workspace.json"
    manifest.parent.mkdir()
    manifest.write_text(json.dumps({"roots": [".", "../library"], "ignore_versions": False}))
    sentinel = host / "src/main/java/bench/PrepSentinel.java"
    sentinel.write_text(
        "package bench; public class PrepSentinel { public static int ready(){ return 7; } }\n"
    )
    for i in range(sources):
        (host / f"src/main/java/bench/Unused{i}.java").write_text(
            f"package bench; public class Unused{i} {{ public int id(){{return {i};}} }}\n"
        )
    operations = []
    for i in range(targets):
        method = f"value{i}"
        provider = library / f"src/main/java/bench/Library{i}.java"
        provider.write_text(
            f"package bench;\npublic class Library{i} {{\n  public static int {method}(int input) {{ return external.Offset.base{i}(input); }}\n}}\n"
        )
        caller = host / f"src/main/java/bench/Caller{i}.java"
        caller.write_text(
            f"package bench;\npublic class Caller{i} {{\n  public int read() {{ return Library{i}.{method}(1); }}\n  public int again() {{ return Library{i}.{method}(2); }}\n}}\n"
        )
        source = caller.read_text()
        declaration = location(provider, provider.read_text(), method)
        references = [declaration]
        offset = 0
        while (offset := source.find(method, offset)) >= 0:
            references.append(location(caller, source, method, offset))
            offset += len(method)
        common = {"target": str(i), "symbol": method, "signature": ["int", "int"], "source": source}
        params = {
            "textDocument": {"uri": caller.as_uri()},
            "position": position(source, source.index(method) + 2),
        }
        for operation in ("definition", "references", "hover", "completion"):
            request = dict(params)
            if operation == "references":
                request["context"] = {"includeDeclaration": True}
            if operation == "completion":
                request["position"] = position(source, source.index(method) + len(method))
            operations.append(
                {
                    **common,
                    "operation": operation,
                    "method": "textDocument/" + operation,
                    "params": request,
                    "expected": (
                        references
                        if operation == "references"
                        else declaration if operation == "definition" else None
                    ),
                }
            )
        dep = f"base{i}"
        text = provider.read_text()
        expected = location(external, external.read_text(), dep)
        expected.update(
            uri="jar:" + attachment.as_uri() + "!/external/Offset.java",
            source=external.read_text(),
            binary_name="offset-1.jar",
            source_name="external/Offset.java",
        )
        operations.append(
            {
                "operation": "dependency_definition",
                "target": str(i),
                "symbol": dep,
                "source": text,
                "method": "textDocument/definition",
                "params": {
                    "textDocument": {"uri": provider.as_uri()},
                    "position": position(text, text.index(dep) + 2),
                },
                "expected": expected,
            }
        )
    files = {
        str(p.relative_to(root)): p.read_text()
        for p in sorted(root.rglob("*"))
        if p.suffix in (".java", ".xml", ".prefs", ".project", ".classpath", ".properties")
    }
    identity = hashlib.sha256(
        json.dumps({k: v.replace(str(root), "$FIXTURE") for k, v in files.items()}, sort_keys=True).encode()
    ).hexdigest()
    return {
        "schema": 2,
        "identity": identity,
        "roots": [str(host), str(library)],
        "repository": str(repository),
        "sentinel": str(sentinel),
        "operations": operations,
        "files": files,
        "dependency": {
            "coordinate": "external:offset:1",
            "binary_sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
            "source_sha256": hashlib.sha256(attachment.read_bytes()).hexdigest(),
        },
        "configuration": "Java 17; JVMD Maven graph + workspace roots; JDTLS Eclipse project reference /library + identical binary/source JAR. No installed local library, no generated host/library classes before server preparation.",
    }
