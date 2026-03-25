package io.mateu.workflow.usersservice.infra.in.grpc;

import io.grpc.stub.StreamObserver;
import io.mateu.demo.lib.AuthServiceGrpc;
import io.mateu.demo.lib.GetAuthInfoReply;
import io.mateu.demo.lib.GetAuthInfoRequest;
import io.mateu.workflow.usersservice.application.query.PermissionQueryService;
import io.mateu.workflow.usersservice.application.query.RoleQueryService;
import io.mateu.workflow.usersservice.application.query.UserQueryService;
import io.mateu.workflow.usersservice.application.query.dto.PermissionDto;
import io.mateu.workflow.usersservice.application.query.dto.RoleDto;
import io.mateu.workflow.usersservice.application.query.dto.UserDto;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;

import java.util.List;
import java.util.stream.Collectors;

@GrpcService
@RequiredArgsConstructor
public class AuthService extends AuthServiceGrpc.AuthServiceImplBase {

    final UserQueryService userQueryService;
    final RoleQueryService roleQueryService;
    final PermissionQueryService permissionQueryService;

    @Override
    public void getAuthInfo(GetAuthInfoRequest request, StreamObserver<GetAuthInfoReply> responseObserver) {
        var userOpt = userQueryService.getById(request.getUser());
        if (userOpt.isEmpty()) {
            // Construimos el error con el código adecuado y una descripción
            responseObserver.onError(io.grpc.Status.NOT_FOUND
                    .withDescription("User with ID " + request.getUser() + " not found")
                    .asRuntimeException());
            return; // ¡Importante! No seguir ejecutando
        }
        GetAuthInfoReply reply = GetAuthInfoReply.newBuilder()
                .setRoles(getRoles(userOpt.get()))
                .setScopes(getScopes(userOpt.get()))
                .build();
        responseObserver.onNext(reply);
        responseObserver.onCompleted();
    }

    private String getScopes(UserDto user) {
        return user.roleIds().stream()                   // Aplanamos: ahora tenemos Stream<String> (roleId)
                .flatMap(roleId -> roleQueryService.getById(roleId).stream())
                .flatMap(role -> roleQueryService.getById(role.id()).stream())
                .map(RoleDto::permissionIds)             // Obtenemos List<String> (permisos)
                .flatMap(List::stream)                   // Aplanamos: ahora tenemos Stream<String> (permissionId)
                .flatMap(permId -> permissionQueryService.getById(permId).stream()) // Buscamos permiso
                .map(PermissionDto::scope)               // Obtenemos el String del scope
                .distinct()                              // Opcional: evita scopes duplicados
                .collect(Collectors.joining(" "));       // Unimos todo con espacios
    }

    private String getRoles(UserDto user) {
        return String.join(" ", user.roleIds());
    }

}
