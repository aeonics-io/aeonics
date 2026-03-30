package aeonics.git;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

import aeonics.entity.Storage;
import aeonics.http.HttpException;
import aeonics.util.Tuples.Tuple;

/**
 * Handles wire protocol requests and responses to map to Operations using Smart Http transport.
 */
public class ProtocolV1
{
	/**
	 * Encodes the advertised Git refs according to the Smart HTTP protocol.
	 *
	 * @param service the requested service ("git-upload-pack" or "git-receive-pack")
	 * @param refs the refs to advertise (ref name -> object SHA)
	 * @return the full packet-line encoded response
	 */
	public static byte[] encodeRefsResponse(String service, Map<String, String> refs)
	{
		try
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			String capabilities = "";
			if( service.equals("git-upload-pack") )
				capabilities = "multi_ack_detailed no-done side-band-64k agent=aeonics-git/1.0";
			else if( service.equals("git-receive-pack") )
				capabilities = "report-status delete-refs atomic side-band-64k agent=aeonics-git/1.0";
			else
				throw new HttpException(400, "Invalid service request");

			writePktLine(out, "# service=" + service);
			writeFlush(out);

			boolean first = true;
			for (Map.Entry<String, String> entry : refs.entrySet())
			{
				String sha = entry.getValue();
				String name = entry.getKey();

				String line = first
					? sha + " " + name + "\0" + capabilities
					: sha + " " + name;

				writePktLine(out, line);
				first = false;
			}

			writeFlush(out);
			return out.toByteArray();
		}
		catch (IOException e)
		{
			throw new HttpException(500, "Failed to encode refs");
		}
	}

	/**
	 * Writes a pkt-line-encoded string and appends a "\n" character at the end.
	 * Do not use this method for side-band encoding.
	 *
	 * @param out the output stream
	 * @param line the line content (excluding length prefix)
	 */
	public static void writePktLine(OutputStream out, String line) throws IOException
	{
		byte[] bytes = (line+"\n").getBytes(StandardCharsets.UTF_8);
		writePktLine(out, bytes);
	}

	/**
	 * Writes a pkt-line-encoded string.
	 *
	 * @param out the output stream
	 * @param line the line content (excluding length prefix)
	 */
	private static void writePktLine(OutputStream out, byte[] line) throws IOException
	{
		if (line.length + 4 > 65520) throw new IOException("pkt-line exceeds maximum allowed size");
		out.write(String.format("%04x", line.length + 4).getBytes(StandardCharsets.US_ASCII));
		out.write(line);
	}

	/**
	 * Writes a flush-pkt ("0000").
	 *
	 * @param out the output stream
	 */
	public static void writeFlush(OutputStream out) throws IOException
	{
		out.write("0000".getBytes(StandardCharsets.US_ASCII));
	}

	/**
	 * Decodes a Git upload-pack request to extract the wants and haves.
	 *
	 * @param body request body decoded as a string
	 * @return a tuple with Set&lt;want&gt; and Set&lt;have&gt;
	 */
	public static Tuple<Set<String>, Set<String>> decodeUploadRequest(String body)
	{
		Set<String> wants = new LinkedHashSet<>();
		Set<String> haves = new LinkedHashSet<>();

		int i = 0;
		boolean firstLine = true;
		while( i < body.length() )
		{
			if( i + 4 > body.length() ) throw new HttpException(400, "Corrupted or invalid pack data header");
			int len = Integer.parseInt(body.substring(i, i + 4), 16);
			if( len == 0 ) { i += 4; continue; } // flush packet
			if( i + len > body.length() ) throw new HttpException(400, "Corrupted or invalid pack data length");

			String line = body.substring(i + 4, i + len).trim();
			if( line.startsWith("want ") )
			{
				int shaEnd = line.indexOf(' ', 5);
				if( shaEnd < 0 ) shaEnd = line.length();
				if( shaEnd - 5 < 40 ) throw new HttpException(400, "Corrupted or invalid pack data line");
				// always capture the SHA
				wants.add(line.substring(5, shaEnd));

				// first line might include capabilities after the SHA
				if( firstLine )
				{
					String[] parts = line.split(" ");
					if( parts.length > 2 )
					{
						boolean hasNoDone = false;
						boolean hasSideBand = false;
						boolean hasMultiAck = false;

						for( int p = 2; p < parts.length; p++ )
						{
							if( parts[p].equals("no-done") ) hasNoDone = true;
							else if( parts[p].equals("side-band-64k") ) hasSideBand = true;
							else if( parts[p].equals("multi_ack_detailed") ) hasMultiAck = true;
						}

						if( !hasNoDone || !hasSideBand || !hasMultiAck )
							throw new HttpException(400, "Missing required capabilities: multi_ack_detailed no-done side-band-64k");
					}
					firstLine = false;
				}
			}
			else if( line.startsWith("have ") )
			{
				int shaEnd = line.indexOf(' ', 5);
				if( shaEnd < 0 ) shaEnd = line.length();
				if( shaEnd - 5 < 40 ) throw new HttpException(400, "Corrupted or invalid pack data line");
				haves.add(line.substring(5, shaEnd));
			}
			else if( line.equals("done") )
			{
				// ignore; marks end of negotiation
			}

			i += len;
		}

		return Tuple.of(wants, haves);
	}

	/**
	 * Encodes a list of Git objects as a packfile upload response.
	 *
	 * @param result the list of Git objects (type, unwrapped content) and the sha of the matched "haves" (can be null)
	 * @return a full packfile wrapped in a side-band stream
	 */
	public static byte[] encodeUploadResponse(Tuple<String, List<Tuple<String, byte[]>>> result)
	{
		try
		{
			String match = result.a;
			List<Tuple<String, byte[]>> objects = result.b;

			ByteArrayOutputStream pack = new ByteArrayOutputStream();

			try( DataOutputStream out = new DataOutputStream(pack) )
			{
				// Write packfile header
				out.writeBytes("PACK");				// 4-byte signature
				out.writeInt(2);					   // version 2
				out.writeInt(objects.size());		  // number of objects

				for( Tuple<String, byte[]> obj : objects )
				{
					String type = obj.a;
					byte[] content = obj.b;
					int typeCode;

					switch (type)
					{
						case "commit": typeCode = 1; break;
						case "tree":   typeCode = 2; break;
						case "blob":   typeCode = 3; break;
						case "tag":	typeCode = 4; break;
						default: throw new IllegalArgumentException("Unsupported object type: " + type);
					}

					// Write object header: variable-length encoding
					int size = content.length;
					int first = (typeCode << 4) | (size & 0x0F);
					size >>>= 4;
					if (size == 0)
						out.writeByte(first);
					else
					{
						out.writeByte(first | 0x80);
						while (true)
						{
							int next = size & 0x7F;
							size >>>= 7;
							if (size == 0)
							{
								out.writeByte(next);
								break;
							}
							out.writeByte(next | 0x80);
						}
					}

					// Compress content (zlib deflate)
					ByteArrayOutputStream deflated = new ByteArrayOutputStream();
					try (DeflaterOutputStream deflater = new DeflaterOutputStream(deflated))
					{
						deflater.write(content);
					}

					out.write(deflated.toByteArray());
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException("Failed to generate upload-pack response", e);
			}

			// Trailer: SHA-1 of entire packfile
			MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
			sha1.update(pack.toByteArray());
			pack.write(sha1.digest());
			byte[] packed = pack.toByteArray();

			// ======================== START RESPONSE
			pack.reset();

			if( match != null )
			{
				writePktLine(pack, "ACK " + match + " ready");
				writePktLine(pack, "ACK " + match);
			}
			else
				writePktLine(pack, "NAK");

			final int MAX_PAYLOAD = 65515;
			int off = 0;
			while( off < packed.length )
			{
				int n = Math.min(MAX_PAYLOAD, packed.length - off);

				// pkt-line length = 4 + 1(band) + n
				int totalLen = 4 + 1 + n;
				String lenHex = String.format("%04x", totalLen);
				pack.write(lenHex.getBytes(StandardCharsets.US_ASCII));
				pack.write(0x01); // band 1 = pack data
				pack.write(packed, off, n);
				off += n;
			}

			writeFlush(pack);
			return pack.toByteArray();
		}
		catch(Exception e)
		{
			throw new RuntimeException("Failed to generate upload-pack response", e);
		}
	}

	/**
	 * Decodes a receive-pack request from a Git client.
	 *
	 * @param data The raw byte array of the request body.
	 * @param store the storage backend for resolving REF_DELTA base objects
	 * @param root the root path of the bare Git repository
	 * @return Tuple of (refs, objects), where refs maps ref name -> (oldSha, newSha), and objects are (type, content)
	 * @throws IllegalArgumentException if deltas or invalid data is found
	 */
	public static Tuple<Map<String, Tuple<String, String>>, List<Tuple<String, byte[]>>> decodeReceiveRequest(byte[] data, Storage.Type store, String root)
	{
		Map<String, Tuple<String, String>> refs = new HashMap<>();
		Map<Integer, Tuple<String, byte[]>> objects = new HashMap<>();
		int pos = 0;

		// === 1. Decode pkt-lines (refs)
		while( true )
		{
			if( pos + 4 > data.length ) throw new IllegalArgumentException("Unexpected end of pkt-line");
			int len = Integer.parseInt(new String(data, pos, 4, StandardCharsets.US_ASCII), 16);
			pos += 4;
			if( len == 0 ) break; // flush-pkt
			if( pos + len - 4 > data.length ) throw new IllegalArgumentException("Incomplete pkt-line");

			String line = new String(data, pos, len - 4, StandardCharsets.UTF_8);
			pos += len - 4;

			String[] parts = line.split("\0", 2);

			// check capabilities
			if( parts.length == 2 && (!parts[1].contains("report-status") || !parts[1].contains("side-band-64k")) )
				throw new RuntimeException("Client must support 'report-status' and 'side-band-64k' capabilities");

			String[] tokens = parts[0].trim().split(" ");
			if( tokens.length == 3 )
				refs.put(tokens[2], Tuple.of(tokens[0], tokens[1]));
			else
				throw new RuntimeException("Malformed pkt-line");
		}

		// === 2. Check PACK header
		int packStart = pos;
		if( pos + 12 > data.length ||
			data[pos] != 'P' || data[pos+1] != 'A' || data[pos+2] != 'C' || data[pos+3] != 'K' )
			throw new IllegalArgumentException("Missing PACK header");

		int version = ((data[pos+4] & 0xFF) << 24) | ((data[pos+5] & 0xFF) << 16) | ((data[pos+6] & 0xFF) << 8) | (data[pos+7] & 0xFF);
		int count = ((data[pos+8] & 0xFF) << 24) | ((data[pos+9] & 0xFF) << 16) | ((data[pos+10] & 0xFF) << 8) | (data[pos+11] & 0xFF);
		pos += 12;

		if( version != 2 )
			throw new IllegalArgumentException("Unsupported Git packfile version: " + version);

		// === 3. Extract objects
		for( int i = 0; i < count; i++ )
		{
			int startOfObject = pos - 1; // needed for OFS deltas
			int b = data[pos++] & 0xFF;
			int type = (b >> 4) & 0x07;

			// skip the size, we dont need it
			while ((b & 0x80) != 0)
				b = data[pos++] & 0xFF;

			Tuple<String, byte[]> deltaBaseObject = null;
			if( type == 6 )
			{
				// this is OFS_DELTA
				int c = data[pos++] & 0xFF;
				int offset = c & 0x7F;
				while( (c & 0x80) != 0 )
				{
					c = data[pos++] & 0xFF;
					offset = ((offset + 1) << 7) | (c & 0x7F);
				}
				int baseOffset = startOfObject - offset;
				deltaBaseObject = objects.get(baseOffset);
			}
			else if( type == 7 )
			{
				// this is REF_DELTA
				deltaBaseObject = Bare.object(
					store,
					root,
					Bare.sha2hex(data, pos));
				pos += 20;
			}

			int compressedStart = pos;

			Inflater inf = new Inflater();
			try {
				inf.setInput(data, compressedStart, data.length - compressedStart);

				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				byte[] out = new byte[4096];

				while( !inf.finished() )
				{
					int n = inf.inflate(out);
					if( n > 0 ) bos.write(out, 0, n);
					else if( inf.needsInput() )
						throw new IllegalArgumentException("truncated deflate stream");
				}

				pos = compressedStart + inf.getTotalIn();   // exact end of this object->s deflate

				String typeStr = null;
				switch(type)
				{
					case 1: typeStr = "commit"; break;
					case 2: typeStr = "tree"; break;
					case 3: typeStr = "blob"; break;
					case 4: typeStr = "tag"; break;
					case 6:
					case 7:
					{
						typeStr = deltaBaseObject.a;
						byte[] resolved = delta(bos.toByteArray(), deltaBaseObject.b);
						bos.reset();
						bos.write(resolved);
						break;
					}
					default: throw new IllegalArgumentException("Unsupported object type: " + type);
				}

				objects.put(startOfObject, Tuple.of(typeStr, bos.toByteArray()));
			}
			catch(Exception e)
			{
				throw new IllegalArgumentException("Failed to inflate object: " + e.getMessage(), e);
			}
		}

		// === 4. Validate packfile checksum
		if( pos + 20 <= data.length )
		{
			try
			{
				MessageDigest md = MessageDigest.getInstance("SHA-1");
				md.update(data, packStart, pos - packStart);
				byte[] computed = md.digest();
				for( int j = 0; j < 20; j++ )
				{
					if( computed[j] != data[pos + j] )
						throw new IllegalArgumentException("Packfile checksum mismatch");
				}
				pos += 20;
			}
			catch( java.security.NoSuchAlgorithmException e )
			{
				throw new RuntimeException("SHA-1 not available", e);
			}
		}

		List<Tuple<String, byte[]>> resolved = List.copyOf(objects.values());
		return Tuple.of(refs, resolved);
	}

	/**
	 * Encodes a list of Git refs status as a packfile receive response.
	 *
	 * @param refs the list of refs
	 * @param message custom success message
	 * @return a full receive response
	 */
	public static byte[] encodeReceiveResponse(Map<String, Tuple<String, String>> refs, String message)
	{
		if( message == null || message.isEmpty() ) message = "Complete.\n";
		if( !message.endsWith("\n") ) message += "\n";

		try( ByteArrayOutputStream out = new ByteArrayOutputStream() )
		{
			byte[] chunk = null;

			// ======== WRITE COMMENTS ON BAND 2
			byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
			int offset = 0;
			while( offset < msgBytes.length )
			{
				int chunkSize = Math.min(65519, msgBytes.length - offset);
				chunk = new byte[chunkSize + 1];
				chunk[0] = 2; // Band 2 = status
				System.arraycopy(msgBytes, offset, chunk, 1, chunkSize);
				writePktLine(out, chunk);
				offset += chunkSize;
			}

			// =========== WRITE STATUS ON BAND 1 as a single line string that contains other pkt-lines
			try( ByteArrayOutputStream status = new ByteArrayOutputStream() )
			{
				status.write(1);
				writePktLine(status, "unpack ok");
				for( String ref : refs.keySet() )
					writePktLine(status, "ok " + ref);
				writeFlush(status);
				status.write('\n');

				writePktLine(out, status.toByteArray());
			}

			writeFlush(out);

			return out.toByteArray();
		}
		catch (IOException e)
		{
			throw new RuntimeException("Failed to encode Git receive response", e);
		}
	}

	/**
	 * Encodes an error response for git-receive-pack using side-band-64k (band 3).
	 *
	 * @param message the error message to send to the client
	 * @return encoded error response
	 */
	public static byte[] encodeReceiveError(String message)
	{
		if( !message.endsWith("\n") ) message += "\n";

		try( ByteArrayOutputStream out = new ByteArrayOutputStream() )
		{
			byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
			int offset = 0;

			while( offset < msgBytes.length )
			{
				int chunkSize = Math.min(65519, msgBytes.length - offset);
				byte[] chunk = new byte[chunkSize + 1];
				chunk[0] = 3; // Band 3 = fatal error
				System.arraycopy(msgBytes, offset, chunk, 1, chunkSize);
				writePktLine(out, chunk);
				offset += chunkSize;
			}

			writeFlush(out);
			return out.toByteArray();
		}
		catch (IOException e)
		{
			throw new RuntimeException("Failed to encode Git error response", e);
		}
	}

	/**
	 * Apply deltas to the specified base object
	 *
	 * @param deltas the input stream containing the deltas
	 * @param base the base object on which deltas should be applied
	 * @return the reconstructed file
	 */
	private static byte[] delta(byte[] deltas, byte[] base) throws IOException
	{
		int pos = 0;

		// first check the base size
		int baseSize = 0;
		int shift = 0;
		while (true)
		{
			int b = deltas[pos++] & 0xFF;
			baseSize |= (long)(b & 0x7F) << shift;
			if ((b & 0x80) == 0) break;
			shift += 7;
		}
		if( baseSize != base.length ) throw new IOException("Base size mismatch");

		// read the result size for later
		int resultSize = 0;
		shift = 0;
		while (true) {
			int b = deltas[pos++] & 0xFF;
			resultSize |= (long)(b & 0x7F) << shift;
			if ((b & 0x80) == 0) break;
			shift += 7;
		}

		// now apply deltas
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		while( pos < deltas.length )
		{
			int opcode = deltas[pos++] & 0xFF;
			if ((opcode & 0x80) != 0)
			{
				// Copy from base
				int offset = 0, size = 0;

				if ((opcode & 0x01) != 0) offset |= (deltas[pos++] & 0xFF);
				if ((opcode & 0x02) != 0) offset |= (deltas[pos++] & 0xFF) << 8;
				if ((opcode & 0x04) != 0) offset |= (deltas[pos++] & 0xFF) << 16;
				if ((opcode & 0x08) != 0) offset |= (deltas[pos++] & 0xFF) << 24;

				if ((opcode & 0x10) != 0) size |= (deltas[pos++] & 0xFF);
				if ((opcode & 0x20) != 0) size |= (deltas[pos++] & 0xFF) << 8;
				if ((opcode & 0x40) != 0) size |= (deltas[pos++] & 0xFF) << 16;

				if (size == 0) size = 0x10000;

				if (offset + size > base.length)
					throw new IOException("Copy exceeds base length");

				out.write(base, offset, size);
			}
			else
			{
				if (pos + opcode > deltas.length)
					throw new IOException("Unexpected EOF in literal insert");

				out.write(deltas, pos, opcode);
				pos += opcode;
			}
		}

		// check the result size
		if( out.size() != resultSize ) throw new IOException("Result size mismatch");

		return out.toByteArray();
	}
}
