grpcurl --plaintext localhost:9091 list
grpcurl --plaintext localhost:9091 list net.devh.boot.grpc.example.MyService
grpcurl --plaintext localhost:9091 list io.mateu.demo.AuthService
# Linux (Static content)
grpcurl --plaintext -d '{"name": "test"}' localhost:9091 net.devh.boot.grpc.example.MyService/SayHello
# Windows or Linux (dynamic content)
grpcurl --plaintext -d "{\"name\": \"test\"}" localhost:9091 net.devh.boot.grpc.example.MyService/SayHello

grpcurl --plaintext -d '{"user": "test"}' localhost:9091 io.mateu.demo.AuthService/GetAuthInfo
grpcurl --plaintext -d '{"user": "miguel"}' localhost:9091 io.mateu.demo.AuthService/GetAuthInfo
