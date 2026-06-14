using System;
using Microsoft.EntityFrameworkCore.Metadata;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace webapi.Migrations
{
    /// <inheritdoc />
    public partial class AddDeviceEntity : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            // Table 'devices' already exists in the database.
            // This migration only syncs the EF Core model snapshot.
            migrationBuilder.Sql("SELECT 1");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            // Table 'devices' pre-existed — do not drop it.
            migrationBuilder.Sql("SELECT 1");
        }
    }
}
