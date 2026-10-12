package fi.haagahelia.accounting.api;

import java.util.List;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI familyBudgetOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Family Budget API")
                .version("0.1.0")
                .description(
                    "Session authentication. Log in through /login "
                        + "before using Swagger UI."
                )
            )
            .components(new Components()
                .addSecuritySchemes(
                    "sessionCookie",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name("JSESSIONID")
                        .description(
                            "Sent automatically by the browser after login."
                        )
                )
                .addSecuritySchemes(
                    "csrfToken",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-CSRF-TOKEN")
                        .description(
                            "Obtain the token from GET /api/auth/csrf."
                        )
                )
            )
            .addSecurityItem(
                new SecurityRequirement().addList("sessionCookie")
            );
    }

    @Bean
    public OpenApiCustomizer apiSecurityDocumentation() {
        return openApi -> {
            addAuthenticationPaths(openApi);

            openApi.getPaths().forEach((path, pathItem) -> {
                if ("/api/auth/csrf".equals(path) && pathItem.getGet() != null) {
                    pathItem.getGet().setSecurity(List.of());
                }

                pathItem.readOperationsMap().forEach((method, operation) -> {
                    if (method == PathItem.HttpMethod.POST
                            || method == PathItem.HttpMethod.PUT
                            || method == PathItem.HttpMethod.PATCH
                            || method == PathItem.HttpMethod.DELETE) {

                        SecurityRequirement requirement =
                            new SecurityRequirement().addList("csrfToken");

                        if (!"/api/auth/login".equals(path)
                                && !"/api/auth/register".equals(path)) {
                            requirement.addList("sessionCookie");
                        }

                        operation.setSecurity(List.of(requirement));
                    }
                });
            });
        };
    }

    private void addAuthenticationPaths(OpenAPI openApi) {
        Schema<Object> loginSchema = new Schema<>();
        loginSchema.setType("object");

        loginSchema.addProperty("username", new StringSchema());
        loginSchema.addProperty(
            "password",
            new StringSchema().format("password").writeOnly(true)
        );

        loginSchema.setRequired(List.of("username", "password"));

        Schema<?> authResponseSchema = new ObjectSchema()
            .addProperty("username", new StringSchema());

        Schema<?> errorSchema = new ObjectSchema()
            .addProperty("status", new IntegerSchema())
            .addProperty("message", new StringSchema());

        Operation login = new Operation()
            .operationId("login")
            .summary("Log in")
            .description(
                "Submit username and password as form fields. "
                    + "A CSRF token is required. After login, obtain a new "
                    + "token from GET /api/auth/csrf."
            )
            .tags(List.of("Authentication"))
            .requestBody(new RequestBody()
                .required(true)
                .content(new Content().addMediaType(
                    "application/x-www-form-urlencoded",
                    new MediaType().schema(loginSchema)
                ))
            )
            .responses(new ApiResponses()
                .addApiResponse("200", new ApiResponse()
                    .description("Authenticated; the session is established")
                    .content(new Content().addMediaType(
                        "application/json",
                        new MediaType().schema(authResponseSchema)
                    ))
                )
                .addApiResponse("401", new ApiResponse()
                    .description("Invalid username or password")
                    .content(new Content().addMediaType(
                        "application/json",
                        new MediaType().schema(errorSchema)
                    ))
                )
                .addApiResponse("403", new ApiResponse()
                    .description("Missing or invalid CSRF token")
                    .content(new Content().addMediaType(
                        "application/json",
                        new MediaType().schema(errorSchema)
                    ))
                )
            );

        Operation logout = new Operation()
            .operationId("logout")
            .summary("Log out")
            .description("Invalidates the session and clears authentication.")
            .tags(List.of("Authentication"))
            .responses(new ApiResponses()
                .addApiResponse("204", new ApiResponse()
                    .description("Logged out")
                )
                .addApiResponse("403", new ApiResponse()
                    .description("Missing or invalid CSRF token")
                    .content(new Content().addMediaType(
                        "application/json",
                        new MediaType().schema(errorSchema)
                    ))
                )
            );

        openApi.path("/api/auth/login", new PathItem().post(login));
        openApi.path("/api/auth/logout", new PathItem().post(logout));
    }
}