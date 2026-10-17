from datetime import datetime
from pathlib import Path
import socket


def main():
    project_dir = Path(__file__).resolve().parent
    capture_dir = project_dir / "request_captures"
    capture_dir.mkdir(exist_ok=True)

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("127.0.0.1", 8000))
        server.listen(1)

        print("Waiting for an Android request on port 8000...", flush=True)

        connection, address = server.accept()

        with connection:
            connection.settimeout(10)
            received = bytearray()

            while b"\r\n\r\n" not in received:
                chunk = connection.recv(4096)

                if not chunk:
                    raise ConnectionError(
                        "Connection closed before HTTP headers were complete."
                    )

                received.extend(chunk)

                if len(received) > 65536:
                    raise ValueError("HTTP headers are too large.")

            filename = datetime.now().strftime("%Y-%m-%d-%H-%M-%S.bin")
            output_path = capture_dir / filename

            with output_path.open("xb") as output:
                output.write(received)

            body = b"[]"
            headers = (
                "HTTP/1.1 200 OK\r\n"
                "Content-Type: application/json; charset=utf-8\r\n"
                f"Content-Length: {len(body)}\r\n"
                "Connection: close\r\n"
                "\r\n"
            ).encode("ascii")

            connection.sendall(headers + body)

            print(f"Client: {address}")
            print(f"Saved request: {output_path}")

    print("Capture finished. Socket server stopped.")


if __name__ == "__main__":
    main()