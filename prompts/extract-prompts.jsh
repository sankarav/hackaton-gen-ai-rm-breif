// Regenerates the prompt mirrors in this folder from the compiled code.
// Run from brief-api/:  mvn -B -q package -DskipTests && mvn -B -q dependency:build-classpath -Dmdep.outputFile=cp.txt
//                       jshell --class-path "target/classes:$(cat cp.txt)" ../prompts/extract-prompts.jsh
import java.nio.file.*;
String field(String cls, String name) throws Exception {
    var f = Class.forName(cls).getDeclaredField(name);
    f.setAccessible(true);
    return (String) f.get(null);
}
var out = Path.of("../prompts");
Files.writeString(out.resolve("synthesis-system-prompt.txt"), field("com.demo.rmbrief.llm.SynthesisService", "SYSTEM_PROMPT"));
Files.writeString(out.resolve("capture-image-transcription-system-prompt.txt"), field("com.demo.rmbrief.capture.transcribe.ImageTranscriber", "SYSTEM") + "\n");
Files.writeString(out.resolve("capture-speaker-labelling-system-prompt.txt"), field("com.demo.rmbrief.capture.extract.SpeakerLabeler", "SYSTEM") + "\n");
Files.writeString(out.resolve("capture-extraction-system-prompt.txt"), field("com.demo.rmbrief.capture.extract.ExtractionService", "SYSTEM") + "\n");
Files.writeString(out.resolve("chat-system-prompt.txt"), field("com.demo.rmbrief.chat.ChatService", "INSTRUCTIONS") + "\n");
// Chat tool definitions, serialized exactly as the SDK sends them.
var tools = new com.demo.rmbrief.chat.ChatTools(null, null, null).definitions();
var mapper = com.anthropic.core.ObjectMappers.jsonMapper();
var node = mapper.createObjectNode();
node.put("_comment", "Tools passed to Claude in brief chat. Built by ChatTools.definitions(). All read-only and bound to the brief's client (no clientId parameter). propose_brief_edit only proposes; the RM applies.");
node.set("tools", mapper.valueToTree(tools));
Files.writeString(out.resolve("chat-tool-definitions.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n");
System.out.println("written");
/exit
